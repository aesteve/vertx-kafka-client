/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vertx.kafka.client.producer.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.kafka.client.producer.KafkaWriteStream;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.Serializer;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka write stream implementation
 */
public class KafkaWriteStreamImpl<K, V> implements KafkaWriteStream<K, V> {

  public static <K, V> KafkaWriteStreamImpl<K, V> create(Vertx vertx, Properties config) {
    return new KafkaWriteStreamImpl<>(vertx.getOrCreateContext(), new org.apache.kafka.clients.producer.KafkaProducer<>(config));
  }

  public static <K, V> KafkaWriteStreamImpl<K, V> create(Vertx vertx, Properties config, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
    return new KafkaWriteStreamImpl<>(vertx.getOrCreateContext(), new org.apache.kafka.clients.producer.KafkaProducer<>(config, keySerializer, valueSerializer));
  }

  public static <K, V> KafkaWriteStreamImpl<K, V> create(Vertx vertx, Map<String, Object> config) {
    return new KafkaWriteStreamImpl<>(vertx.getOrCreateContext(), new org.apache.kafka.clients.producer.KafkaProducer<>(config));
  }

  public static <K, V> KafkaWriteStreamImpl<K, V> create(Vertx vertx, Map<String, Object> config, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
    return new KafkaWriteStreamImpl<>(vertx.getOrCreateContext(), new org.apache.kafka.clients.producer.KafkaProducer<>(config, keySerializer, valueSerializer));
  }

  private long maxSize = DEFAULT_MAX_SIZE;
  private long pending;
  private final Producer<K, V> producer;
  private Handler<Void> drainHandler;
  private Handler<Throwable> exceptionHandler;
  private final Context context;

  public KafkaWriteStreamImpl(Context context, Producer<K, V> producer) {
    this.producer = producer;
    this.context = context;
  }

  private int len(Object value) {
    if (value instanceof byte[]) {
      return ((byte[])value).length;
    } else if (value instanceof String) {
      return ((String)value).length();
    } else {
      return 1;
    }
  }

  @Override
  public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
    ContextInternal ctx = (ContextInternal) context.owner().getOrCreateContext();
    Promise<RecordMetadata> trampolineProm = ctx.promise();
    int len = this.len(record.value());
    this.pending += len;
    this.context.<RecordMetadata>executeBlocking(prom -> {
      try {
        this.producer.send(record, (metadata, err) -> {

          // callback from IO thread
          this.context.runOnContext(v1 -> {
            synchronized (KafkaWriteStreamImpl.this) {

              // if exception happens, no record written
              if (err != null) {

                if (this.exceptionHandler != null) {
                  Handler<Throwable> exceptionHandler = this.exceptionHandler;
                  this.context.runOnContext(v2 -> exceptionHandler.handle(err));
                }
              }

              long lowWaterMark = this.maxSize / 2;
              this.pending -= len;
              if (this.pending < lowWaterMark && this.drainHandler != null) {
                Handler<Void> drainHandler = this.drainHandler;
                this.drainHandler = null;
                this.context.runOnContext(drainHandler);
              }
            }
          });

          if (err != null) {
            prom.fail(err);
          } else {
            prom.complete(metadata);
          }
        });
      } catch (Throwable e) {
        synchronized (KafkaWriteStreamImpl.this) {
          if (this.exceptionHandler != null) {
            Handler<Throwable> exceptionHandler = this.exceptionHandler;
            this.context.runOnContext(v3 -> exceptionHandler.handle(e));
          }
        }
        prom.fail(e);
      }
    }).setHandler(trampolineProm);
    return trampolineProm.future(); // Trampoline on caller context
  }

  @Override
  public synchronized KafkaWriteStreamImpl<K, V> send(ProducerRecord<K, V> record, Handler<AsyncResult<RecordMetadata>> handler) {
    this.send(record).setHandler(handler);
    return this;
  }

  @Override
  public void write(ProducerRecord<K, V> data, Handler<AsyncResult<Void>> handler) {
    this.write(data).setHandler(handler);
  }

  @Override
  public Future<Void> write(ProducerRecord<K, V> record) {
    return this.send(record).mapEmpty();
  }

  @Override
  public KafkaWriteStreamImpl<K, V> setWriteQueueMaxSize(int size) {
    this.maxSize = size;
    return this;
  }

  @Override
  public synchronized boolean writeQueueFull() {
    return (this.pending >= this.maxSize);
  }

  @Override
  public synchronized KafkaWriteStreamImpl<K, V> drainHandler(Handler<Void> handler) {
    this.drainHandler = handler;
    return this;
  }

  @Override
  public void end(Handler<AsyncResult<Void>> handler) {
    if (handler != null) {
      context.runOnContext(v -> handler.handle(Future.succeededFuture()));
    }
  }

  @Override
  public KafkaWriteStream<K, V> initTransactions(Handler<AsyncResult<Void>> handler) {
    return executeBlocking(handler, this.producer::initTransactions);
  }

  @Override
  public KafkaWriteStream<K, V> beginTransaction(Handler<AsyncResult<Void>> handler) {
    return executeBlocking(handler, this.producer::beginTransaction);
  }

  @Override
  public KafkaWriteStream<K, V> commitTransaction(Handler<AsyncResult<Void>> handler) {
    return executeBlocking(handler, this.producer::commitTransaction);
  }

  @Override
  public KafkaWriteStream<K, V> abortTransaction(Handler<AsyncResult<Void>> handler) {
    return executeBlocking(handler, this.producer::abortTransaction);
  }

  @Override
  public KafkaWriteStreamImpl<K, V> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  @Override
  public Future<List<PartitionInfo>> partitionsFor(String topic) {
    ContextInternal ctx = (ContextInternal) context.owner().getOrCreateContext();
    Promise<List<PartitionInfo>> trampolineProm = ctx.promise();

    // TODO: should be this timeout related to the Kafka producer property "metadata.fetch.timeout.ms" ?
    this.context.owner().setTimer(2000, id -> {
      trampolineProm.tryFail("Kafka connect timeout");
    });

    this.context.<List<PartitionInfo>>executeBlocking(prom -> {
      prom.complete(
        this.producer.partitionsFor(topic)
      );
    }).setHandler(trampolineProm);

    return trampolineProm.future(); // Trampoline on caller context
  }

  @Override
  public KafkaWriteStreamImpl<K, V> partitionsFor(String topic, Handler<AsyncResult<List<PartitionInfo>>> handler) {
    partitionsFor(topic).setHandler(handler);
    return this;
  }

  @Override
  public Future<Void> flush() {
    ContextInternal ctx = (ContextInternal) context.owner().getOrCreateContext();
    Promise<Void> trampolineProm = ctx.promise();
    this.context.<Void>executeBlocking(prom -> {
      this.producer.flush();
      prom.complete();
    }).setHandler(trampolineProm);
    return trampolineProm.future(); // Trampoline on caller context
  }

  @Override
  public KafkaWriteStreamImpl<K, V> flush(Handler<AsyncResult<Void>> completionHandler) {
    flush().setHandler(completionHandler);
    return this;
  }

  @Override
  public Future<Void> close() {
    return close(0);
  }

  @Override
  public void close(Handler<AsyncResult<Void>> completionHandler) {
    close(0, completionHandler);
  }

  @Override
  public Future<Void> close(long timeout) {
    ContextInternal ctx = (ContextInternal) context.owner().getOrCreateContext();
    Promise<Void> trampolineProm = ctx.promise();
    this.context.<Void>executeBlocking(prom -> {
      if (timeout > 0) {
        this.producer.close(timeout, TimeUnit.MILLISECONDS);
      } else {
        this.producer.close();
      }
      prom.complete();
    }).setHandler(trampolineProm);
    return trampolineProm.future(); // Trampoline on caller context
  }

  @Override
  public void close(long timeout, Handler<AsyncResult<Void>> completionHandler) {
    close(timeout).setHandler(completionHandler);
  }

  @Override
  public Producer<K, V> unwrap() {
    return this.producer;
  }

  private KafkaWriteStreamImpl<K, V> executeBlocking(final Handler<AsyncResult<Void>> handler, final BlockingStatement statement) {
    this.context.executeBlocking(promise -> {
      try {
        statement.execute();
        promise.complete();
      } catch (Exception e) {
        promise.fail(e);
      }
    }, handler);
    return this;
  }

  @FunctionalInterface
  private interface BlockingStatement {

    void execute();
  }

}
