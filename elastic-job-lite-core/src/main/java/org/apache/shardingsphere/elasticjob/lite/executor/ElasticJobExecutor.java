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

package org.apache.shardingsphere.elasticjob.lite.executor;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.elasticjob.lite.api.ElasticJob;
import org.apache.shardingsphere.elasticjob.lite.api.ShardingContext;
import org.apache.shardingsphere.elasticjob.lite.config.JobRootConfiguration;
import org.apache.shardingsphere.elasticjob.lite.event.type.JobExecutionEvent;
import org.apache.shardingsphere.elasticjob.lite.event.type.JobExecutionEvent.ExecutionSource;
import org.apache.shardingsphere.elasticjob.lite.event.type.JobStatusTraceEvent.State;
import org.apache.shardingsphere.elasticjob.lite.exception.ExceptionUtil;
import org.apache.shardingsphere.elasticjob.lite.exception.JobExecutionEnvironmentException;
import org.apache.shardingsphere.elasticjob.lite.exception.JobSystemException;
import org.apache.shardingsphere.elasticjob.lite.executor.handler.ExecutorServiceHandler;
import org.apache.shardingsphere.elasticjob.lite.executor.handler.ExecutorServiceHandlerRegistry;
import org.apache.shardingsphere.elasticjob.lite.executor.handler.JobExceptionHandler;
import org.apache.shardingsphere.elasticjob.lite.executor.handler.JobProperties.JobPropertiesEnum;
import org.apache.shardingsphere.elasticjob.lite.executor.type.JobItemExecutor;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * ElasticJob executor.
 */
@Slf4j
public final class ElasticJobExecutor {
    
    private final ElasticJob elasticJob;
    
    private final JobFacade jobFacade;
    
    private final JobRootConfiguration jobRootConfig;
    
    private final String jobName;
    
    private final ExecutorService executorService;
    
    private final JobExceptionHandler jobExceptionHandler;
    
    private final Map<Integer, String> itemErrorMessages;
    
    private final JobItemExecutor jobItemExecutor;
    
    public ElasticJobExecutor(final ElasticJob elasticJob, final JobFacade jobFacade, final JobItemExecutor jobItemExecutor) {
        this.elasticJob = elasticJob;
        this.jobFacade = jobFacade;
        jobRootConfig = jobFacade.loadJobRootConfiguration(true);
        jobName = jobRootConfig.getTypeConfig().getCoreConfig().getJobName();
        executorService = ExecutorServiceHandlerRegistry.getExecutorServiceHandler(jobName, (ExecutorServiceHandler) getHandler(JobPropertiesEnum.EXECUTOR_SERVICE_HANDLER));
        jobExceptionHandler = (JobExceptionHandler) getHandler(JobPropertiesEnum.JOB_EXCEPTION_HANDLER);
        itemErrorMessages = new ConcurrentHashMap<>(jobRootConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(), 1);
        this.jobItemExecutor = jobItemExecutor;
    }
    
    private Object getHandler(final JobPropertiesEnum jobPropertiesEnum) {
        String handlerClassName = jobRootConfig.getTypeConfig().getCoreConfig().getJobProperties().get(jobPropertiesEnum);
        try {
            Class<?> handlerClass = Class.forName(handlerClassName);
            if (jobPropertiesEnum.getClassType().isAssignableFrom(handlerClass)) {
                return handlerClass.newInstance();
            }
            return getDefaultHandler(jobPropertiesEnum, handlerClassName);
        } catch (final ReflectiveOperationException ex) {
            return getDefaultHandler(jobPropertiesEnum, handlerClassName);
        }
    }
    
    private Object getDefaultHandler(final JobPropertiesEnum jobPropertiesEnum, final String handlerClassName) {
        log.warn("Cannot instantiation class '{}', use default '{}' class.", handlerClassName, jobPropertiesEnum.getKey());
        try {
            return Class.forName(jobPropertiesEnum.getDefaultValue()).newInstance();
        } catch (final ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new JobSystemException(e);
        }
    }
    
    /**
     * Execute job.
     */
    public void execute() {
        try {
            jobFacade.checkJobExecutionEnvironment();
        } catch (final JobExecutionEnvironmentException cause) {
            jobExceptionHandler.handleException(jobName, cause);
        }
        ShardingContexts shardingContexts = jobFacade.getShardingContexts();
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_STAGING, String.format("Job '%s' execute begin.", jobName));
        }
        if (jobFacade.misfireIfRunning(shardingContexts.getShardingItemParameters().keySet())) {
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format(
                        "Previous job '%s' - shardingItems '%s' is still running, misfired job will start after previous job completed.", jobName, 
                        shardingContexts.getShardingItemParameters().keySet()));
            }
            return;
        }
        try {
            jobFacade.beforeJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
        execute(shardingContexts, ExecutionSource.NORMAL_TRIGGER);
        while (jobFacade.isExecuteMisfired(shardingContexts.getShardingItemParameters().keySet())) {
            jobFacade.clearMisfire(shardingContexts.getShardingItemParameters().keySet());
            execute(shardingContexts, ExecutionSource.MISFIRE);
        }
        jobFacade.failoverIfNecessary();
        try {
            jobFacade.afterJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
    }
    
    private void execute(final ShardingContexts shardingContexts, final ExecutionSource executionSource) {
        if (shardingContexts.getShardingItemParameters().isEmpty()) {
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format("Sharding item for job '%s' is empty.", jobName));
            }
            return;
        }
        jobFacade.registerJobBegin(shardingContexts);
        String taskId = shardingContexts.getTaskId();
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobStatusTraceEvent(taskId, State.TASK_RUNNING, "");
        }
        try {
            process(shardingContexts, executionSource);
        } finally {
            // TODO Consider increasing the status of job failure, and how to handle the overall loop of job failure
            jobFacade.registerJobCompleted(shardingContexts);
            if (itemErrorMessages.isEmpty()) {
                if (shardingContexts.isAllowSendJobEvent()) {
                    jobFacade.postJobStatusTraceEvent(taskId, State.TASK_FINISHED, "");
                }
            } else {
                if (shardingContexts.isAllowSendJobEvent()) {
                    jobFacade.postJobStatusTraceEvent(taskId, State.TASK_ERROR, itemErrorMessages.toString());
                }
            }
        }
    }
    
    private void process(final ShardingContexts shardingContexts, final ExecutionSource executionSource) {
        Collection<Integer> items = shardingContexts.getShardingItemParameters().keySet();
        if (1 == items.size()) {
            int item = shardingContexts.getShardingItemParameters().keySet().iterator().next();
            JobExecutionEvent jobExecutionEvent = new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, item);
            process(shardingContexts, item, jobExecutionEvent);
            return;
        }
        final CountDownLatch latch = new CountDownLatch(items.size());
        for (final int each : items) {
            JobExecutionEvent jobExecutionEvent = new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, each);
            if (executorService.isShutdown()) {
                return;
            }
            executorService.submit(() -> {
                try {
                    process(shardingContexts, each, jobExecutionEvent);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
    
    @SuppressWarnings("unchecked")
    private void process(final ShardingContexts shardingContexts, final int item, final JobExecutionEvent startEvent) {
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobExecutionEvent(startEvent);
        }
        log.trace("Job '{}' executing, item is: '{}'.", jobName, item);
        JobExecutionEvent completeEvent;
        try {
            jobItemExecutor.process(elasticJob, jobRootConfig, jobFacade, new ShardingContext(shardingContexts, item));
            completeEvent = startEvent.executionSuccess();
            log.trace("Job '{}' executed, item is: '{}'.", jobName, item);
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobExecutionEvent(completeEvent);
            }
            // CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            // CHECKSTYLE:ON
            completeEvent = startEvent.executionFailure(cause);
            jobFacade.postJobExecutionEvent(completeEvent);
            itemErrorMessages.put(item, ExceptionUtil.transform(cause));
            jobExceptionHandler.handleException(jobName, cause);
        }
    }
}
