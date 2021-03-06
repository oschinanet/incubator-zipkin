/*
 * Copyright 2015-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.elasticsearch;

import com.squareup.moshi.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okio.Buffer;
import okio.ByteString;
import zipkin2.Annotation;
import zipkin2.Call;
import zipkin2.Span;
import zipkin2.codec.SpanBytesEncoder;
import zipkin2.elasticsearch.internal.HttpBulkIndexer;
import zipkin2.elasticsearch.internal.IndexNameFormatter;
import zipkin2.internal.DelayLimiter;
import zipkin2.storage.SpanConsumer;

import static zipkin2.elasticsearch.ElasticsearchAutocompleteTags.AUTOCOMPLETE;
import static zipkin2.elasticsearch.ElasticsearchSpanStore.SPAN;
import static zipkin2.internal.JsonEscaper.jsonEscape;
import static zipkin2.internal.JsonEscaper.jsonEscapedSizeInBytes;

class ElasticsearchSpanConsumer implements SpanConsumer { // not final for testing
  static final Logger LOG = Logger.getLogger(ElasticsearchSpanConsumer.class.getName());
  static final int INDEX_CHARS_LIMIT = 256;
  static final ByteString EMPTY_JSON = ByteString.of(new byte[] {'{', '}'});

  final ElasticsearchStorage es;
  final Set<String> autocompleteKeys;
  final IndexNameFormatter indexNameFormatter;
  final boolean searchEnabled;
  final DelayLimiter<AutocompleteContext> delayLimiter;

  ElasticsearchSpanConsumer(ElasticsearchStorage es) {
    this.es = es;
    this.autocompleteKeys = new LinkedHashSet<>(es.autocompleteKeys());
    this.indexNameFormatter = es.indexNameFormatter();
    this.searchEnabled = es.searchEnabled();
    this.delayLimiter = DelayLimiter.newBuilder()
      .ttl(es.autocompleteTtl())
      .cardinality(es.autocompleteCardinality()).build();
  }

  @Override public Call<Void> accept(List<Span> spans) {
    if (spans.isEmpty()) return Call.create(null);
    BulkSpanIndexer indexer = new BulkSpanIndexer(this);
    indexSpans(indexer, spans);
    return indexer.newCall();
  }

  void indexSpans(BulkSpanIndexer indexer, List<Span> spans) {
    for (Span span : spans) {
      long spanTimestamp = span.timestampAsLong();
      long indexTimestamp = 0L; // which index to store this span into
      if (spanTimestamp != 0L) {
        indexTimestamp = spanTimestamp = TimeUnit.MICROSECONDS.toMillis(spanTimestamp);
      } else {
        // guessTimestamp is made for determining the span's authoritative timestamp. When choosing
        // the index bucket, any annotation is better than using current time.
        for (int i = 0, length = span.annotations().size(); i < length; i++) {
          indexTimestamp = span.annotations().get(i).timestamp() / 1000;
          break;
        }
        if (indexTimestamp == 0L) indexTimestamp = System.currentTimeMillis();
      }
      indexer.add(indexTimestamp, span, spanTimestamp);
      if (searchEnabled && !span.tags().isEmpty()) {
        indexer.addAutocompleteValues(indexTimestamp, span);
      }
    }
  }

  /** Mutable type used for each call to store spans */
  static final class BulkSpanIndexer {
    final HttpBulkIndexer indexer;
    final ElasticsearchSpanConsumer consumer;
    final List<AutocompleteContext> pendingAutocompleteContexts = new ArrayList<>();

    BulkSpanIndexer(ElasticsearchSpanConsumer consumer) {
      this.indexer = new HttpBulkIndexer("index-span", consumer.es);
      this.consumer = consumer;
    }

    void add(long indexTimestamp, Span span, long timestampMillis) {
      String index = consumer.indexNameFormatter
        .formatTypeAndTimestamp(SPAN, indexTimestamp);
      byte[] document = consumer.searchEnabled
        ? prefixWithTimestampMillisAndQuery(span, timestampMillis)
        : SpanBytesEncoder.JSON_V2.encode(span);
      indexer.add(index, SPAN, document, null /* Allow ES to choose an ID */);
    }

    void addAutocompleteValues(long indexTimestamp, Span span) {
      String idx = consumer.indexNameFormatter.formatTypeAndTimestamp(AUTOCOMPLETE, indexTimestamp);
      for (Map.Entry<String, String> tag : span.tags().entrySet()) {
        int length = tag.getKey().length() + tag.getValue().length() + 1;
        if (length > INDEX_CHARS_LIMIT) continue;

        // If the autocomplete whitelist doesn't contain the key, skip storing its value
        if (!consumer.autocompleteKeys.contains(tag.getKey())) continue;

        // Id is used to dedupe server side as necessary. Arbitrarily same format as _q value.
        String id = tag.getKey() + "=" + tag.getValue();
        AutocompleteContext context = new AutocompleteContext(indexTimestamp, id);
        if (!consumer.delayLimiter.shouldInvoke(context)) continue;
        pendingAutocompleteContexts.add(context);

        // encode using zipkin's internal buffer so we don't have to catch exceptions etc
        int sizeInBytes = 27; // {"tagKey":"","tagValue":""}
        sizeInBytes += jsonEscapedSizeInBytes(tag.getKey());
        sizeInBytes += jsonEscapedSizeInBytes(tag.getValue());
        zipkin2.internal.Buffer b = zipkin2.internal.Buffer.allocate(sizeInBytes);
        b.writeAscii("{\"tagKey\":\"");
        b.writeUtf8(jsonEscape(tag.getKey()));
        b.writeAscii("\",\"tagValue\":\"");
        b.writeUtf8(jsonEscape(tag.getValue()));
        b.writeAscii("\"}");
        byte[] document = b.toByteArray();
        indexer.add(idx, AUTOCOMPLETE, document, id);
      }
    }

    Call<Void> newCall() {
      Call<Void> storeCall = indexer.newCall();
      if (pendingAutocompleteContexts.isEmpty()) return storeCall;
      return storeCall.handleError((error, callback) -> {
        for (AutocompleteContext context : pendingAutocompleteContexts) {
          consumer.delayLimiter.invalidate(context);
        }
        callback.onError(error);
      });
    }
  }

  /**
   * In order to allow systems like Kibana to search by timestamp, we add a field "timestamp_millis"
   * when storing. The cheapest way to do this without changing the codec is prefixing it to the
   * json. For example. {"traceId":"... becomes {"timestamp_millis":12345,"traceId":"...
   *
   * <p>Tags are stored as a dictionary. Since some tag names will include inconsistent number of
   * dots (ex "error" and perhaps "error.message"), we cannot index them naturally with
   * elasticsearch. Instead, we add an index-only (non-source) field of {@code _q} which includes
   * valid search queries. For example, the tag {@code error -> 500} results in {@code
   * "_q":["error", "error=500"]}. This matches the input query syntax, and can be checked manually
   * with curl.
   *
   * <p>Ex {@code curl -s localhost:9200/zipkin:span-2017-08-11/_search?q=_q:error=500}
   */
  static byte[] prefixWithTimestampMillisAndQuery(Span span, long timestampMillis) {
    Buffer prefix = new Buffer();
    JsonWriter writer = JsonWriter.of(prefix);
    try {
      writer.beginObject();

      if (timestampMillis != 0L) writer.name("timestamp_millis").value(timestampMillis);
      if (!span.tags().isEmpty() || !span.annotations().isEmpty()) {
        writer.name("_q");
        writer.beginArray();
        for (Annotation a : span.annotations()) {
          if (a.value().length() > INDEX_CHARS_LIMIT) continue;
          writer.value(a.value());
        }
        for (Map.Entry<String, String> tag : span.tags().entrySet()) {
          int length = tag.getKey().length() + tag.getValue().length() + 1;
          if (length > INDEX_CHARS_LIMIT) continue;
          writer.value(tag.getKey()); // search is possible by key alone
          writer.value(tag.getKey() + "=" + tag.getValue());
        }
        writer.endArray();
      }
      writer.endObject();
    } catch (IOException e) {
      // very unexpected to have an IOE for an in-memory write
      assert false : "Error indexing query for span: " + span;
      if (LOG.isLoggable(Level.FINE)) {
        LOG.log(Level.FINE, "Error indexing query for span: " + span, e);
      }
      return SpanBytesEncoder.JSON_V2.encode(span);
    }
    byte[] document = SpanBytesEncoder.JSON_V2.encode(span);
    if (prefix.rangeEquals(0L, EMPTY_JSON)) return document;
    return mergeJson(prefix.readByteArray(), document);
  }

  static byte[] mergeJson(byte[] prefix, byte[] suffix) {
    byte[] newSpanBytes = new byte[prefix.length + suffix.length - 1];
    int pos = 0;
    System.arraycopy(prefix, 0, newSpanBytes, pos, prefix.length);
    pos += prefix.length;
    newSpanBytes[pos - 1] = ',';
    // starting at position 1 discards the old head of '{'
    System.arraycopy(suffix, 1, newSpanBytes, pos, suffix.length - 1);
    return newSpanBytes;
  }

  static final class AutocompleteContext {
    final long indexTimestamp;
    final String autocompleteId;

    AutocompleteContext(long indexTimestamp, String autocompleteId) {
      this.indexTimestamp = indexTimestamp;
      this.autocompleteId = autocompleteId;
    }

    @Override public boolean equals(Object o) {
      if (o == this) return true;
      if (!(o instanceof AutocompleteContext)) return false;
      AutocompleteContext that = (AutocompleteContext) o;
      return indexTimestamp == that.indexTimestamp && autocompleteId.equals(that.autocompleteId);
    }

    @Override public int hashCode() {
      int h$ = 1;
      h$ *= 1000003;
      h$ ^= (int) (h$ ^ ((indexTimestamp >>> 32) ^ indexTimestamp));
      h$ *= 1000003;
      h$ ^= autocompleteId.hashCode();
      return h$;
    }
  }
}
