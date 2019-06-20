package com.ebs.inspector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupResult;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.AttributeValueUpdate;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.dynamodbv2.model.UpdateItemResult;
import com.amazonaws.services.elasticbeanstalk.model.EnvironmentDescription;
import com.ebs.inspector.utils.Constants;

@Component
public class ElasticBeanstalkActionService {
	private static Logger LOGGER = LoggerFactory.getLogger(ElasticBeanstalkActionService.class);
	private static final String _1 = "1";
	@Autowired
	private Environment environment;

	public void suspendEnvironment(EnvironmentDescription environmentDesc, String autoScalingGroupName) {
		LOGGER.info("Suspending environment = {}", environmentDesc.getEnvironmentName());

		LOGGER.info("AutoScalingGroup => Find AutoScalingGroup configuration for Autoscaling group name = {}",
				autoScalingGroupName);
		final AmazonDynamoDB dynamoDbClient = AmazonDynamoDBClientBuilder.defaultClient();
		final AmazonAutoScaling autoScalingClient = AmazonAutoScalingClientBuilder.standard().build();

		DescribeAutoScalingGroupsResult describeAutoScalingGroupsResult = autoScalingClient.describeAutoScalingGroups(
				new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(autoScalingGroupName));
		LOGGER.info("AutoScalingGroup => AutoScalingGroup configuration = {}", describeAutoScalingGroupsResult);

		LOGGER.debug("DynamoDB => Saving AutoscalingGroup configuration");
		UpdateItemRequest updateItemRequest = new UpdateItemRequest()
				.withTableName(environment.getProperty(Constants.EB_ENV_METADATA_TABLE_NM))
				.addKeyEntry(Constants.EB_APPLICATION_NAME, new AttributeValue(environmentDesc.getApplicationName()))
				.addKeyEntry(Constants.EB_ENVIRONMENT_NAME, new AttributeValue(environmentDesc.getEnvironmentName()))
				.addAttributeUpdatesEntry(Constants.EB_AUTOSCALING_MIN_CAPACITY,
						new AttributeValueUpdate()
								.withValue(new AttributeValue().withN(String.valueOf(describeAutoScalingGroupsResult
										.getAutoScalingGroups().get(0).getMinSize().toString()))))
				.addAttributeUpdatesEntry(Constants.EB_AUTOSCALING_DESIRED_CAPACITY,
						new AttributeValueUpdate()
								.withValue(new AttributeValue().withN(String.valueOf(describeAutoScalingGroupsResult
										.getAutoScalingGroups().get(0).getDesiredCapacity().toString()))));

		LOGGER.info("DynamoDB => UpdateItemRequest = {}", updateItemRequest);
		UpdateItemResult updateItemResult = dynamoDbClient.updateItem(updateItemRequest);
		LOGGER.info("DynamoDB => UpdateItemResult = {}", updateItemResult);

		UpdateAutoScalingGroupRequest updateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest()
				.withAutoScalingGroupName(autoScalingGroupName).withDesiredCapacity(0).withMinSize(0);
		LOGGER.info("UpdateAutoScalingGroupRequest = {}", updateAutoScalingGroupRequest);
		UpdateAutoScalingGroupResult updateAutoScalingGroupResult = autoScalingClient
				.updateAutoScalingGroup(updateAutoScalingGroupRequest);
		LOGGER.info("UpdateAutoScalingGroupResult = {}", updateAutoScalingGroupResult);
	}

	public void resumeEnvironment(EnvironmentDescription environmentDesc, String autoScalingGroupName) {
		LOGGER.info("Resuming environment = {}", environmentDesc.getEnvironmentName());

		final AmazonDynamoDB dynamoDbClient = AmazonDynamoDBClientBuilder.defaultClient();
		final AmazonAutoScaling autoScalingClient = AmazonAutoScalingClientBuilder.standard().build();

		GetItemRequest itemRequest = new GetItemRequest()
				.withTableName(environment.getProperty(Constants.EB_ENV_METADATA_TABLE_NM))
				.addKeyEntry(Constants.EB_APPLICATION_NAME, new AttributeValue(environmentDesc.getApplicationName()))
				.addKeyEntry(Constants.EB_ENVIRONMENT_NAME, new AttributeValue(environmentDesc.getEnvironmentName()));
		LOGGER.info("DynamoDB => GetItemRequest = {}", itemRequest);
		GetItemResult itemResult = dynamoDbClient.getItem(itemRequest);
		LOGGER.info("DynamoDB => GetItemResult = {}", itemResult);
		
		UpdateAutoScalingGroupRequest updateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest()
				.withAutoScalingGroupName(autoScalingGroupName)
				.withMinSize(new Integer(itemResult.getItem()
						.getOrDefault(Constants.EB_AUTOSCALING_MIN_CAPACITY, new AttributeValue(_1)).getN()))
				.withDesiredCapacity(new Integer(itemResult.getItem()
						.getOrDefault(Constants.EB_AUTOSCALING_DESIRED_CAPACITY, new AttributeValue(_1)).getN()));
		
		LOGGER.info("UpdateAutoScalingGroupRequest = {}", updateAutoScalingGroupRequest);
		UpdateAutoScalingGroupResult updateAutoScalingGroupResult = autoScalingClient
				.updateAutoScalingGroup(updateAutoScalingGroupRequest);
		LOGGER.info("UpdateAutoScalingGroupResult = {}", updateAutoScalingGroupResult);
	}
}