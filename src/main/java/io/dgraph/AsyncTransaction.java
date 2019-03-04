/*
 * Copyright (C) 2018 Dgraph Labs, Inc. and Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.dgraph;

import io.dgraph.DgraphGrpc.DgraphStub;
import io.dgraph.DgraphProto.*;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the implementation of asynchronous Dgraph transaction. The asynchrony is backed-up by
 * underlying grpc implementation.
 *
 * @author Edgar Rodriguez-Diaz
 * @author Deepak Jois
 * @author Michail Klimenkov
 */
public class AsyncTransaction implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AsyncTransaction.class);

  // these can potentially be set from different threads executing the Stub callback
  private volatile TxnContext context;
  private volatile boolean mutated;
  private volatile boolean finished;
  private volatile boolean readOnly;

  private final DgraphAsyncClient client;
  private final DgraphStub stub;

  AsyncTransaction(DgraphAsyncClient client, DgraphStub stub) {
    this.context = TxnContext.newBuilder().build();
    this.client = client;
    this.stub = stub;
    this.readOnly = false;
  }

  AsyncTransaction(DgraphAsyncClient client, DgraphStub stub, final boolean readOnly) {
    this(client, stub);
    this.readOnly = readOnly;
  }

  /**
   * sends a query to one of the connected dgraph instances. If no mutations need to be made in the
   * same transaction, it's convenient to chain the method: <code>
   * client.NewTransaction().queryWithVars(...)</code>.
   *
   * @param query Query in GraphQL+-
   * @param vars variables referred to in the queryWithVars.
   * @return a Response protocol buffer object.
   */
  public CompletableFuture<Response> queryWithVars(
      final String query, final Map<String, String> vars) {
    LOG.debug("Starting query...");

    LinRead.Builder lr = LinRead.newBuilder(context.getLinRead());
    final Request request =
        Request.newBuilder()
            .setQuery(query)
            .putAllVars(vars)
            .setStartTs(context.getStartTs())
            .setLinRead(lr.build())
            .setReadOnly(readOnly)
            .build();

    LOG.debug("Sending request to Dgraph...");
    return client.runWithRetries(
        "query",
        () -> {
          StreamObserverBridge<Response> bridge = new StreamObserverBridge<>();
          DgraphStub localStub = client.getStubWithJwt(stub);
          localStub.query(request, bridge);
          return bridge
              .getDelegate()
              .thenApply(
                  (response) -> {
                    LOG.debug("Received response from Dgraph!");
                    mergeContext(response.getTxn());
                    return response;
                  });
        });
  }

  /**
   * Calls {@code Transcation#queryWithVars} with an empty vars map.
   *
   * @param query Query in GraphQL+-
   * @return a Response protocol buffer object
   */
  public CompletableFuture<Response> query(final String query) {
    return queryWithVars(query, Collections.emptyMap());
  }

  /**
   * Allows data stored on dgraph instances to be modified. The fields in Mutation come in pairs,
   * set and delete. Mutations can either be encoded as JSON or as RDFs.
   *
   * <p>If the commitNow property on the Mutation object is set,
   *
   * @param mutation a Mutation protocol buffer object representing the mutation.
   * @return an Assigned protocol buffer object. his call will result in the transaction being
   *     committed. In this case, an explicit call to AsyncTransaction#commit doesn't need to
   *     subsequently be made.
   */
  public CompletableFuture<Assigned> mutate(Mutation mutation) {
    if (readOnly) {
      throw new TxnReadOnlyException();
    }
    if (finished) {
      throw new TxnFinishedException();
    }

    Mutation request = Mutation.newBuilder(mutation).setStartTs(context.getStartTs()).build();

    return client.runWithRetries(
        "mutation",
        () -> {
          StreamObserverBridge<Assigned> bridge = new StreamObserverBridge<>();
          DgraphStub localStub = client.getStubWithJwt(stub);
          localStub.mutate(request, bridge);

          return bridge
              .getDelegate()
              .thenApply(
                  (assigned) -> {
                    mutated = true;
                    if (mutation.getCommitNow()) {
                      finished = true;
                    }
                    mergeContext(assigned.getContext());
                    return assigned;
                  });
        });
  }

  /**
   * Commits any mutations that have been made in the transaction. Once Commit has been called, the
   * lifespan of the transaction is complete.
   *
   * <p>Errors could be thrown for various reasons. Notably, a StatusRuntimeException could be
   * thrown if transactions that modify the same data are being run concurrently. It's up to the
   * user to decide if they wish to retry. In this case, the user should create a new transaction.
   *
   * @return CompletableFuture with Void result
   */
  public CompletableFuture<Void> commit() {
    if (readOnly) {
      throw new TxnReadOnlyException();
    }
    if (finished) {
      throw new TxnFinishedException();
    }

    finished = true;

    if (!mutated) {
      return CompletableFuture.completedFuture(null);
    }

    return client.runWithRetries(
        "commit",
        () -> {
          StreamObserverBridge<TxnContext> bridge = new StreamObserverBridge<>();
          DgraphStub localStub = client.getStubWithJwt(stub);
          localStub.commitOrAbort(context, bridge);
          return bridge.getDelegate().thenApply(txnContext -> null);
        });
  }

  /**
   * Cleans up the resources associated with an uncommitted transaction that contains mutations. It
   * is a no-op on transactions that have already been committed or don't contain mutations.
   * Therefore it is safe (and recommended) to call it in a finally block.
   *
   * <p>In some cases, the transaction can't be discarded, e.g. the grpc connection is unavailable.
   * In these cases, the server will eventually do the transaction clean up.
   *
   * @return CompletableFuture with Void result
   */
  public CompletableFuture<Void> discard() {
    if (finished) {
      return CompletableFuture.completedFuture(null);
    }
    finished = true;

    if (!mutated) {
      return CompletableFuture.completedFuture(null);
    }

    context = TxnContext.newBuilder(context).setAborted(true).build();
    return client.runWithRetries(
        "discard",
        () -> {
          StreamObserverBridge<TxnContext> bridge = new StreamObserverBridge<>();
          DgraphStub localStub = client.getStubWithJwt(stub);
          localStub.commitOrAbort(context, bridge);
          return bridge.getDelegate().thenApply((o) -> null);
        });
  }

  private void mergeContext(final TxnContext src) {
    TxnContext.Builder builder = TxnContext.newBuilder(context);

    if (context.getStartTs() == 0) {
      builder.setStartTs(src.getStartTs());
    } else if (context.getStartTs() != src.getStartTs()) {
      this.context = builder.build();
      throw new DgraphException("startTs mismatch");
    }

    builder.addAllKeys(src.getKeysList());
    builder.addAllPreds(src.getPredsList());

    this.context = builder.build();
  }

  @Override
  public void close() {
    discard().join();
  }
}
