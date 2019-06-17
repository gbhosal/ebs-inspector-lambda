package com.ebs.inspector.function;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClientBuilder;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsResult;
import com.amazonaws.services.elasticbeanstalk.model.EnvironmentDescription;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Ganesh Bhosale
 */
@Component("FindAllEbEnvironmentsFunction")
public class FindAllEbEnvironmentsFunction implements Function<ScheduledEvent, String> {
	private static Logger LOGGER = LoggerFactory.getLogger(FindAllEbEnvironmentsFunction.class);	
	@Autowired
	private Environment environment;
	@Autowired
	private ObjectMapper mapper;
	
	/** 
	 * Entry point of function business logic
	 */
	@Override
	public String apply(ScheduledEvent scheduleEvent) {
		LOGGER.info("ScheduledEvent = {}", scheduleEvent);
				
		AWSElasticBeanstalk awsElasticBeanstalkClient = AWSElasticBeanstalkClientBuilder.standard().build();
		String nextToken = null;
		while(true) {
			DescribeEnvironmentsRequest request = new DescribeEnvironmentsRequest()
					.withMaxRecords(100)
					.withNextToken(nextToken);
			DescribeEnvironmentsResult describeEnvironmentsResult = awsElasticBeanstalkClient.describeEnvironments(request);
			LOGGER.info("DescribeEnvironmentsResult = {}", describeEnvironmentsResult);			
			sendEnvironmentDetailsToSqs(describeEnvironmentsResult);			
			if (describeEnvironmentsResult.getNextToken() == null) {
				break;
			}
			nextToken = describeEnvironmentsResult.getNextToken();
		}
		
		return "SUCCESS";
	}

	private void sendEnvironmentDetailsToSqs(DescribeEnvironmentsResult describeEnvironmentsResult) {
		AmazonSQS sqs = AmazonSQSClientBuilder.standard().build();
		List<SendMessageBatchRequestEntry> entries = new ArrayList<>(100);
		if (describeEnvironmentsResult == null
				|| CollectionUtils.isEmpty(describeEnvironmentsResult.getEnvironments())) {
			return;
		}
		describeEnvironmentsResult.getEnvironments().forEach(environmentDescription -> {
			SendMessageBatchRequestEntry request = new SendMessageBatchRequestEntry();
			request.setMessageBody(convertToValue(environmentDescription));
			entries.add(request);
		});
		sqs.sendMessageBatch(environment.getRequiredProperty(Constant.SQS_QUEUE_URL), entries);
	}
	
	private String convertToValue(EnvironmentDescription environmentDescription) {
		try {
			return mapper.writeValueAsString(environmentDescription);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public static class Constant {
		private static final String SQS_QUEUE_URL="SQS_QUEUE_URL";
	}
}