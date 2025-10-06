package com.codurance.limiter.metrics;

import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;

public class CloudWatchMetricPublisherTest {
  @Test
  public void cloudWatchClientCalledOnMetricPublish() {
    CloudWatchClient mock = org.mockito.Mockito.mock(CloudWatchClient.class);
    CloudWatchMetricPublisher pub = new CloudWatchMetricPublisher(mock, "TestNS");

    pub.incrementCounter("FlushedBatches", 3);
    pub.gauge("PendingBufferSize", 10);

    // verify that putMetricData was called at least once
    verify(mock, org.mockito.Mockito.atLeastOnce()).putMetricData(any(PutMetricDataRequest.class));
  }
}
