/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.executor.transport;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.crate.analyze.CreateSnapshotAnalyzedStatement;
import io.crate.analyze.DropSnapshotAnalyzedStatement;
import io.crate.analyze.RestoreSnapshotAnalyzedStatement;
import io.crate.exceptions.CreateSnapshotException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest;
import org.elasticsearch.action.admin.cluster.snapshots.delete.DeleteSnapshotResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.snapshots.SnapshotState;

import static io.crate.analyze.SnapshotSettings.*;



@Singleton
public class SnapshotDDLDispatcher {

    private static final ESLogger LOGGER = Loggers.getLogger(SnapshotDDLDispatcher.class);
    private final TransportActionProvider transportActionProvider;

    @Inject
    public SnapshotDDLDispatcher(TransportActionProvider transportActionProvider) {
        this.transportActionProvider = transportActionProvider;
    }

    public ListenableFuture<Long> dispatch(final DropSnapshotAnalyzedStatement statement) {
        final SettableFuture<Long> future = SettableFuture.create();
        final String repositoryName = statement.repository();
        final String snapshotName = statement.snapshot();

        transportActionProvider.transportDeleteSnapshotAction().execute(
                new DeleteSnapshotRequest(repositoryName, snapshotName),
                new ActionListener<DeleteSnapshotResponse>() {
                    @Override
                    public void onResponse(DeleteSnapshotResponse response) {
                        if (!response.isAcknowledged()) {
                            LOGGER.info("delete snapshot '{}.{}' not acknowledged", repositoryName, snapshotName);
                        }
                        future.set(1L);
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        future.setException(e);
                    }
                }
        );
        return future;

    }

    public ListenableFuture<Long> dispatch(final CreateSnapshotAnalyzedStatement statement) {
        final SettableFuture<Long> resultFuture = SettableFuture.create();

        boolean waitForCompletion = statement.snapshotSettings().getAsBoolean(WAIT_FOR_COMPLETION.settingName(), WAIT_FOR_COMPLETION.defaultValue());
        boolean ignoreUnavailable = statement.snapshotSettings().getAsBoolean(IGNORE_UNAVAILABLE.settingName(), IGNORE_UNAVAILABLE.defaultValue());

        // ignore_unavailable as set by statement
        IndicesOptions indicesOptions = IndicesOptions.fromOptions(ignoreUnavailable, true, true, false, IndicesOptions.lenientExpandOpen());


        CreateSnapshotRequest request = new CreateSnapshotRequest(statement.snapshotId().getRepository(), statement.snapshotId().getSnapshot())
                .includeGlobalState(statement.includeMetadata())
                .waitForCompletion(waitForCompletion)
                .indices(statement.indices())
                .indicesOptions(indicesOptions)
                .settings(statement.snapshotSettings());

        //noinspection ThrowableResultOfMethodCallIgnored
        assert request.validate() == null : "invalid CREATE SNAPSHOT statement";
        transportActionProvider.transportCreateSnapshotAction().execute(request, new ActionListener<CreateSnapshotResponse>() {
            @Override
            public void onResponse(CreateSnapshotResponse createSnapshotResponse) {
                if (createSnapshotResponse.getSnapshotInfo().state() == SnapshotState.FAILED) {
                    // fail request if snapshot creation failed
                    String reason = createSnapshotResponse.getSnapshotInfo().reason()
                            .replaceAll("Index", "Table")
                            .replaceAll("Indices", "Tables");
                    resultFuture.setException(
                            new CreateSnapshotException(statement.snapshotId(), reason)
                    );
                } else {
                    resultFuture.set(1L);
                }
            }

            @Override
            public void onFailure(Throwable e) {
                resultFuture.setException(e);
            }
        });
        return resultFuture;
    }

    private class RestoreDispatcher {
        private final boolean waitForCompletion;
        private final boolean partial;
        private final IndicesOptions indicesOptions;

        private final RestoreSnapshotAnalyzedStatement analysis;

        private final SettableFuture<Long> resultFuture = SettableFuture.create();

        private RestoreDispatcher(RestoreSnapshotAnalyzedStatement analysis) {
            this.analysis = analysis;
            waitForCompletion = analysis.settings().getAsBoolean(WAIT_FOR_COMPLETION.settingName(), WAIT_FOR_COMPLETION.defaultValue());
            partial = analysis.settings().getAsBoolean(PARTIAL.settingName(), PARTIAL.defaultValue());
            boolean ignoreUnavailable = analysis.settings().getAsBoolean(IGNORE_UNAVAILABLE.settingName(), IGNORE_UNAVAILABLE.defaultValue());
            indicesOptions = IndicesOptions.fromOptions(ignoreUnavailable, true, true, false, IndicesOptions.lenientExpandOpen());
        }

        ListenableFuture<Long> dispatch() {
            if (analysis.restoreAll() || !analysis.indices().isEmpty()) {
                restoreTables();
            } else {
                restorePartitions();
            }
            return resultFuture;
        }

        void restorePartitions() {
            // second pass for partitions incl. creating aliases but without global state
            RestoreSnapshotRequest partitionsRequest = new RestoreSnapshotRequest(analysis.repositoryName(), analysis.snapshotName())
                    .indices(analysis.partitions())
                    .includeGlobalState(false)
                    .includeAliases(true)
                    .settings(analysis.settings())
                    .waitForCompletion(waitForCompletion)
                    .partial(partial);
            transportActionProvider.transportRestoreSnapshotAction().execute(partitionsRequest, new ActionListener<RestoreSnapshotResponse>() {
                @Override
                public void onResponse(RestoreSnapshotResponse restoreSnapshotResponse) {
                    resultFuture.set(1L);
                }

                @Override
                public void onFailure(Throwable e) {
                    resultFuture.setException(e);
                }
            });
        }

        void restoreTables() {
            RestoreSnapshotRequest request = new RestoreSnapshotRequest(analysis.repositoryName(), analysis.snapshotName())
                    .indices(analysis.indices())
                    .indicesOptions(indicesOptions)
                    .settings(analysis.settings())
                    .waitForCompletion(waitForCompletion)
                    .includeGlobalState(analysis.includeMetadata())
                    .includeAliases(analysis.includeMetadata())
                    .partial(partial);
            transportActionProvider.transportRestoreSnapshotAction().execute(request, new ActionListener<RestoreSnapshotResponse>() {
                @Override
                public void onResponse(RestoreSnapshotResponse restoreSnapshotResponse) {
                    if (!analysis.partitions().isEmpty()) {
                        restorePartitions();
                    } else {
                        resultFuture.set(1L);
                    }
                }

                @Override
                public void onFailure(Throwable e) {
                    resultFuture.setException(e);
                }
            });
        }
    }

    public ListenableFuture<Long> dispatch(final RestoreSnapshotAnalyzedStatement analysis) {
        return new RestoreDispatcher(analysis).dispatch();
    }
}
