/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.elasticjob.lite.executor.type;

import org.apache.shardingsphere.elasticjob.lite.exception.JobSystemException;
import org.apache.shardingsphere.elasticjob.lite.executor.ElasticJobExecutor;
import org.apache.shardingsphere.elasticjob.lite.executor.JobFacade;
import org.apache.shardingsphere.elasticjob.lite.executor.ShardingContexts;
import org.apache.shardingsphere.elasticjob.lite.fixture.ShardingContextsBuilder;
import org.apache.shardingsphere.elasticjob.lite.fixture.config.TestScriptJobConfiguration;
import org.apache.shardingsphere.elasticjob.lite.fixture.handler.IgnoreJobExceptionHandler;
import org.apache.shardingsphere.elasticjob.lite.fixture.handler.ThrowJobExceptionHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class ScriptJobExecutorTest {
    
    @Mock
    private JobFacade jobFacade;
    
    private ElasticJobExecutor elasticJobExecutor;
    
    @Test
    public void assertExecuteWhenCommandLineIsEmpty() {
        ElasticJobVerify.prepareForIsNotMisfire(jobFacade, ShardingContextsBuilder.getMultipleShardingContexts());
        when(jobFacade.loadJobRootConfiguration(true)).thenReturn(new TestScriptJobConfiguration("", IgnoreJobExceptionHandler.class));
        elasticJobExecutor = new ElasticJobExecutor(null, jobFacade, new ScriptJobExecutor());
        elasticJobExecutor.execute();
    }
    
    @Test(expected = JobSystemException.class)
    public void assertExecuteWhenExecuteFailureForSingleShardingItems() {
        assertExecuteWhenExecuteFailure(ShardingContextsBuilder.getSingleShardingContexts());
    }
    
    @Test
    public void assertExecuteWhenExecuteFailureForMultipleShardingItems() {
        assertExecuteWhenExecuteFailure(ShardingContextsBuilder.getMultipleShardingContexts());
    }
    
    private void assertExecuteWhenExecuteFailure(final ShardingContexts shardingContexts) {
        ElasticJobVerify.prepareForIsNotMisfire(jobFacade, shardingContexts);
        when(jobFacade.loadJobRootConfiguration(true)).thenReturn(new TestScriptJobConfiguration("not_exists_file", ThrowJobExceptionHandler.class));
        elasticJobExecutor = new ElasticJobExecutor(null, jobFacade, new ScriptJobExecutor());
        elasticJobExecutor.execute();
    }
    
    @Test
    public void assertExecuteSuccessForMultipleShardingItems() {
        assertExecuteSuccess(ShardingContextsBuilder.getMultipleShardingContexts());
    }
    
    @Test
    public void assertExecuteSuccessForSingleShardingItems() {
        assertExecuteSuccess(ShardingContextsBuilder.getSingleShardingContexts());
    }
    
    private void assertExecuteSuccess(final ShardingContexts shardingContexts) {
        ElasticJobVerify.prepareForIsNotMisfire(jobFacade, shardingContexts);
        when(jobFacade.loadJobRootConfiguration(true)).thenReturn(new TestScriptJobConfiguration("exists_file param0 param1", IgnoreJobExceptionHandler.class));
        elasticJobExecutor = new ElasticJobExecutor(null, jobFacade, new ScriptJobExecutor());
        elasticJobExecutor.execute();
        verify(jobFacade).loadJobRootConfiguration(true);
    }
}
