package com.ebs.inspector.function;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder;
import com.amazonaws.services.autoscaling.model.DescribeTagsRequest;
import com.amazonaws.services.autoscaling.model.DescribeTagsResult;
import com.amazonaws.services.autoscaling.model.Filter;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupResult;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClientBuilder;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentResourcesRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentResourcesResult;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentsResult;
import com.amazonaws.services.elasticbeanstalk.model.EnvironmentDescription;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.ebs.inspector.exception.InvalidTimeWindowException;

/**
 * @author Ganesh Bhosale
 */
@Component("EbsInspectorFunction")
public class EbsInspectorFunction implements Function<ScheduledEvent, String>{
	private static Logger LOGGER = LoggerFactory.getLogger(EbsInspectorFunction.class);	
	@Autowired
	private Environment environment;
	
	/** 
	 * Entry point of function business logic
	 */
	@Override
	public String apply(ScheduledEvent scheduleEvent) {
		LOGGER.info("ScheduledEvent = {}", scheduleEvent);
		boolean offBusinessHours = offBusinessHours();
		LOGGER.info("Off Business Hours = {}", offBusinessHours);
				
		AWSElasticBeanstalk awsElasticBeanstalkClient = AWSElasticBeanstalkClientBuilder.standard().build();
		String nextToken = null;
		while(true) {
			DescribeEnvironmentsRequest request = new DescribeEnvironmentsRequest()
					.withMaxRecords(100)
					.withNextToken(nextToken);
			DescribeEnvironmentsResult response = awsElasticBeanstalkClient.describeEnvironments(request);
			LOGGER.info("DescribeEnvironmentsResult = {}", response);
			inspectEnvironments(response, offBusinessHours);			
			if (response.getNextToken() == null) {
				break;
			}
			nextToken = response.getNextToken();
		}
		
		return "SUCCESS";
	}

	private void inspectEnvironments(DescribeEnvironmentsResult response, boolean offBusinessHours) {
		if (response == null || CollectionUtils.isEmpty(response.getEnvironments())) {
			return;
		}
		response.getEnvironments().forEach(environment -> this.inspectEbsEnvironment(offBusinessHours, environment));
	}

	private void inspectEbsEnvironment(boolean offBusinessHours, EnvironmentDescription environment) {
		if (offBusinessHours && "Grey".equalsIgnoreCase(environment.getHealth())) {
			LOGGER.info("Environment {} is already down. No action needed.", environment.getEnvironmentName());
			return;
		}
		
		AWSElasticBeanstalk awsElasticBeanstalkClient = AWSElasticBeanstalkClientBuilder.standard().build();
		AmazonAutoScaling amazonAutoScalingClient = AmazonAutoScalingClientBuilder.standard().build();
		
		DescribeEnvironmentResourcesRequest describeEnvironmentResourcesRequest = new DescribeEnvironmentResourcesRequest()
				.withEnvironmentName(environment.getEnvironmentName());
		LOGGER.info("DescribeEnvironmentResourcesRequest = {}", describeEnvironmentResourcesRequest);
		DescribeEnvironmentResourcesResult describeEnvironmentResourcesResult = awsElasticBeanstalkClient
				.describeEnvironmentResources(describeEnvironmentResourcesRequest);
		LOGGER.info("DescribeEnvironmentResourcesResult = {}", describeEnvironmentResourcesResult);
		
		String  autoScalingGroupName = getAutoScalingGroupName(describeEnvironmentResourcesResult);
		DescribeTagsRequest describeTagsRequest = new DescribeTagsRequest()
				.withFilters(new Filter().withName("auto-scaling-group")
						.withValues(autoScalingGroupName));
		LOGGER.info("DescribeTagsRequest = {}", describeTagsRequest);
		DescribeTagsResult describeTagsResult = amazonAutoScalingClient.describeTags(describeTagsRequest);
		LOGGER.info("DescribeTagsResult = {}", describeTagsResult);

		if (!autoSuspensionEnabled(describeTagsResult)) {
			LOGGER.info("Environment {} isn't enabled for Auto-Suspension", environment.getEnvironmentName());
			return;
		}
		
		if (offBusinessHours) {
			LOGGER.info("Suspending environment = {}", environment.getEnvironmentName());				
			UpdateAutoScalingGroupRequest updateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest()
					.withAutoScalingGroupName(autoScalingGroupName).withDesiredCapacity(0).withMinSize(0);
			LOGGER.info("UpdateAutoScalingGroupRequest = {}", updateAutoScalingGroupRequest);
			UpdateAutoScalingGroupResult updateAutoScalingGroupResult = amazonAutoScalingClient
					.updateAutoScalingGroup(updateAutoScalingGroupRequest);
			LOGGER.info("UpdateAutoScalingGroupResult = {}", updateAutoScalingGroupResult);
			
			// Grey Health represents "No Data" Health Status, which is equivalent to "Suspend"
		} else if ("Grey".equalsIgnoreCase(environment.getHealth())) {
			LOGGER.info("Enabling environment = {}", environment.getEnvironmentName());
			UpdateAutoScalingGroupRequest updateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest()
					.withAutoScalingGroupName(autoScalingGroupName).withMinSize(1).withDesiredCapacity(1);
			LOGGER.info("UpdateAutoScalingGroupRequest = {}", updateAutoScalingGroupRequest);
			UpdateAutoScalingGroupResult updateAutoScalingGroupResult = amazonAutoScalingClient
					.updateAutoScalingGroup(updateAutoScalingGroupRequest);
			LOGGER.info("UpdateAutoScalingGroupResult = {}", updateAutoScalingGroupResult);
		}
	}

	private boolean autoSuspensionEnabled(DescribeTagsResult describeTagsResult) {
		if (describeTagsResult == null || CollectionUtils.isEmpty(describeTagsResult.getTags())) {
			return false;
		}
		return describeTagsResult.getTags().stream()
				.filter(x -> Constant.CONSTANTS_AUTO_SUSPEND.equalsIgnoreCase(x.getKey())
						&& Constant.CONSTANTS_TRUE.equalsIgnoreCase(x.getValue()))
				.findFirst().isPresent();
	}

	private boolean offBusinessHours() {
		String offBusinessHourWindow = environment.getProperty(Constant.OFF_BUSINESS_HOUR_WINDOW);
		if (!StringUtils.hasText(offBusinessHourWindow)) {
			return false;
		}
		if (isOffDayOfWeek()) {
			return true;
		}
		String[] offBusinessHourWindows = offBusinessHourWindow.split(",");
		return Stream.of(offBusinessHourWindows).filter(this::offBusinessHourWindow).findFirst().isPresent();
	}

	private boolean isOffDayOfWeek() {
		ZonedDateTime systemDateTime = LocalDateTime.now().atZone(ZoneId.systemDefault());
		ZonedDateTime targetZonedDateTime = systemDateTime.withZoneSameInstant(getOffBusinessHourTimezone());
		
		String offDayOfWeeks = environment.getProperty(Constant.OFF_BUSINESS_DAY_OF_WEEK);
		if (!StringUtils.hasText(offDayOfWeeks)) {
			return false;
		}
		String[] offDayOfWeek = offDayOfWeeks.split(",");
		return Stream.of(offDayOfWeek).filter(e -> targetZonedDateTime.getDayOfWeek() == DayOfWeek.valueOf(e))
				.findFirst().isPresent();
	}
	
	private ZoneId getOffBusinessHourTimezone() {
		return StringUtils.hasText(environment.getProperty(Constant.OFF_BUSINESS_HOUR_TIMEZONE))
				? ZoneId.of(environment.getProperty(Constant.OFF_BUSINESS_HOUR_TIMEZONE))
				: ZoneId.systemDefault();
	}
	
	private boolean offBusinessHourWindow(String window) {		
		ZonedDateTime systemDateTime = LocalDateTime.now().atZone(ZoneId.systemDefault());
		ZonedDateTime targetZonedDateTime = systemDateTime.withZoneSameInstant(getOffBusinessHourTimezone());
		String[] startAndEndHours = window.split(Constant.HYPEN);
		if (startAndEndHours == null || startAndEndHours.length != 2) {
			throw new InvalidTimeWindowException(
					"Invalid Timewindow. Valid format is HH:MM-HH:MM where HH can be 0-23 and MM can be 0-59");
		}
		if (LocalTime.parse(startAndEndHours[0]).equals(LocalTime.parse(startAndEndHours[1]))
				|| LocalTime.parse(startAndEndHours[0]).isAfter(LocalTime.parse(startAndEndHours[1]))) {
			throw new InvalidTimeWindowException(
					"Invalid Timewindow " + window + ". Start time has be before End time");
		}
		LocalTime localTime = targetZonedDateTime.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
		return (localTime.equals(LocalTime.parse(startAndEndHours[0])) || localTime.isAfter(LocalTime.parse(startAndEndHours[0])))
			&& (localTime.equals(LocalTime.parse(startAndEndHours[1])) || localTime.isBefore(LocalTime.parse(startAndEndHours[1])));
	}

	private String getAutoScalingGroupName(DescribeEnvironmentResourcesResult describeEnvironmentResourcesResult) {
		return describeEnvironmentResourcesResult.getEnvironmentResources().getAutoScalingGroups().get(0).getName();
	}
	
	public static class Constant {
		private final static String CONSTANTS_AUTO_SUSPEND = "AutoSuspend";
		private final static String CONSTANTS_TRUE = "true";
		private static final String HYPEN = "-";
		private static final String OFF_BUSINESS_HOUR_TIMEZONE = "OFF_BUSINESS_HOUR_TIMEZONE";
		private static final String OFF_BUSINESS_HOUR_WINDOW = "OFF_BUSINESS_HOUR_WINDOW";
		private static final String OFF_BUSINESS_DAY_OF_WEEK = "OFF_BUSINESS_DAY_OF_WEEK";
	}
}