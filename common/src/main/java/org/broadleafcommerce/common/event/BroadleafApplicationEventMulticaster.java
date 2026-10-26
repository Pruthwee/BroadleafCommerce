/*-
 * #%L
 * BroadleafCommerce Common Libraries
 * %%
 * Copyright (C) 2009 - 2026 Broadleaf Commerce
 * %%
 * Licensed under the Broadleaf Fair Use License Agreement, Version 1.0
 * (the "Fair Use License" located  at http://license.broadleafcommerce.org/fair_use_license-1.0.txt)
 * unless the restrictions on use therein are violated and require payment to Broadleaf in which case
 * the Broadleaf End User License Agreement (EULA), Version 1.1
 * (the "Commercial License" located at http://license.broadleafcommerce.org/commercial_license-1.1.txt)
 * shall apply.
 * 
 * Alternatively, the Commercial License may be replaced with a mutually agreed upon license (the "Custom License")
 * between you and Broadleaf Commerce. You may not use this file except in compliance with the applicable license.
 * #L%
 */
package org.broadleafcommerce.common.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.ResolvableType;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.util.concurrent.Executor;

/**
 * This class is a simple extension to Spring's SimpleApplicationEventMulticaster.  The difference is
 * that this allows the EventListener to indicate whether it should be asynchronous or not, assuming that a
 * TaskExecutor has been configured.
 * <p>
 * Asynchronous execution should be used with care.  Events are not durable with this implementation.
 * In addition, this implementation does not broadcast or multicast events to systems outside of the
 * running JVM, although an event listener could be configured to do just that.
 * <p>
 * In cloud deployments, IP multicast is replaced with Amazon SNS fan-out for event broadcasting (cr-java-0080).
 *
 * @author Kelly Tisdell
 */
public class BroadleafApplicationEventMulticaster extends SimpleApplicationEventMulticaster implements ApplicationContextAware {

    private static final Log LOG = LogFactory.getLog(BroadleafApplicationEventMulticaster.class);

    protected ApplicationContext ctx;
    @Autowired(required = false)
    @Qualifier("blApplicationEventMulticastTaskExecutor")
    private Executor taskExecutor;

    @Value("${aws.sns.topic.arn:}")
    protected String snsTopicArn;

    // Lazy SNS client - built on first use, never in static initializer
    protected SnsClient snsClient;

    /**
     * Take care when specifying that event or application listener should be executed asynchronously.
     * If there is no TaskExecutor configured, this
     * will execute synchronously, regardless.  If there is a TaskExecutor configured, then if the
     * listener is a BroadleafApplicationListener and its
     * <code>isAsynchronous()</code> method returns true then the event will fire asynchronously.
     * Be aware that the events are not durable in this case.  Events that are executed asynchronously
     * should be used with caution, where a loss of event due to error or shutdown of the VM is not a major
     * concern.
     */
    @Override
    public void multicastEvent(final ApplicationEvent event) {
        Executor executor = getTaskExecutor();
        for (final ApplicationListener<?> listener : getApplicationListeners(event, ResolvableType.forInstance(event))) {
            boolean isAsynchronous = false;
            if (executor != null) {
                if ((BroadleafApplicationListener.class.isAssignableFrom(listener.getClass())
                        && ((BroadleafApplicationListener<? extends ApplicationEvent>) listener).isAsynchronous())) {
                    isAsynchronous = true;
                }
            }

            if (isAsynchronous) {
                executor.execute(new Runnable() {
                    public void run() {
                        invokeListener(listener, event);
                    }
                });
            } else {
                invokeListener(listener, event);
            }
        }
        // Also publish to SNS for cloud fan-out (cr-java-0080: Replace IP multicast with Amazon SNS fan-out)
        if (snsTopicArn != null && !snsTopicArn.isEmpty()) {
            publishToSns(event);
        }
    }

    protected void publishToSns(ApplicationEvent event) {
        try {
            String message = event.getClass().getName() + ":" + event.toString();
            PublishRequest request = PublishRequest.builder()
                    .topicArn(snsTopicArn)
                    .message(message)
                    .build();
            getSnsClient().publish(request);
        } catch (SnsException e) {
            LOG.warn("Error publishing event to SNS", e);
        }
    }

    protected SnsClient getSnsClient() {
        if (snsClient == null) {
            try {
                snsClient = SnsClient.builder()
                        .region(Region.of(System.getenv("AWS_REGION")))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
            } catch (SdkException e) {
                LOG.error("Unable to create SNS client", e);
            }
        }
        return snsClient;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.ctx = applicationContext;
    }

    public Executor getTaskExecutor() {
        return taskExecutor;
    }

    public void setTaskExecutor(Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

}
