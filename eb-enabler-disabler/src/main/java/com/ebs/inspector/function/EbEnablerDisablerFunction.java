package com.ebs.inspector.function;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
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
import com.amazonaws.services.autoscaling.model.TagDescription;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClientBuilder;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentResourcesRequest;
import com.amazonaws.services.elasticbeanstalk.model.DescribeEnvironmentResourcesResult;
import com.amazonaws.services.elasticbeanstalk.model.EnvironmentDescription;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.ebs.inspector.exception.InvalidTimeWindowException;
import com.ebs.inspector.service.ElasticBeanstalkActionService;
import com.ebs.inspector.utils.Constants;
import com.ebs.inspector.vo.Tag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Ganesh Bhosale
 */
@Component("EbEnablerDisablerFunction")
public class EbEnablerDisablerFunction implements Function<SQSEvent, String>, InitializingBean {
	private static Logger LOGGER = LoggerFactory.getLogger(EbEnablerDisablerFunction.class);
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private Environment environment;
	private List<Tag> tagFilter; 
	@Autowired
	private ElasticBeanstalkActionService elasticBeanstalkActionService;

	@Override
	public void afterPropertiesSet() throws Exception {
		if (!StringUtils.isEmpty(environment.getProperty("TAG_FILTER"))) {
			tagFilter = convertToValue(environment.getProperty("TAG_FILTER"), new TypeReference<List<Tag>>() {});
			LOGGER.info("Tag Filter = {}", tagFilter);
		}
	}
	
	/**
	 * Entry point of function business logic
	 */
	@Override
	public String apply(SQSEvent sqsMessage) {
		boolean offBusinessHours = offBusinessHours();
		LOGGER.info("SQS Message = {}", sqsMessage);
		List<EnvironmentDescription> environmentDescriptionList = sqsMessage.getRecords().stream()
				.map(e -> this.convertToValue(e.getBody(), EnvironmentDescription.class)).collect(Collectors.toList());
		environmentDescriptionList.parallelStream().forEach(
				environmentDescription -> this.inspectEbsEnvironment(offBusinessHours, environmentDescription));
		return "SUCCESS";
	}

	private void inspectEbsEnvironment(boolean offBusinessHours, EnvironmentDescription environment) {
		if (offBusinessHours && Constants.ENV_HEALTH_GREY.equalsIgnoreCase(environment.getHealth())) {
			LOGGER.info("Environment {} is already down. No action needed.", environment.getEnvironmentName());
			return;
		}

		AWSElasticBeanstalk elasticBeanstalkClient = AWSElasticBeanstalkClientBuilder.standard().build();
		AmazonAutoScaling autoScalingClient = AmazonAutoScalingClientBuilder.standard().build();

		DescribeEnvironmentResourcesRequest describeEnvironmentResourcesRequest = new DescribeEnvironmentResourcesRequest()
				.withEnvironmentName(environment.getEnvironmentName());
		LOGGER.info("DescribeEnvironmentResourcesRequest = {}", describeEnvironmentResourcesRequest);
		DescribeEnvironmentResourcesResult describeEnvironmentResourcesResult = elasticBeanstalkClient
				.describeEnvironmentResources(describeEnvironmentResourcesRequest);
		LOGGER.info("DescribeEnvironmentResourcesResult = {}", describeEnvironmentResourcesResult);

		String autoScalingGroupName = getAutoScalingGroupName(describeEnvironmentResourcesResult);
		DescribeTagsRequest describeTagsRequest = new DescribeTagsRequest()
				.withFilters(new Filter().withName("auto-scaling-group").withValues(autoScalingGroupName));
		LOGGER.info("DescribeTagsRequest = {}", describeTagsRequest);
		DescribeTagsResult describeTagsResult = autoScalingClient.describeTags(describeTagsRequest);
		LOGGER.info("DescribeTagsResult = {}", describeTagsResult);

		if (!autoSuspensionEnabled(describeTagsResult)) {
			LOGGER.info("Environment {} isn't enabled for Auto-Suspension", environment.getEnvironmentName());
			return;
		}

		if (offBusinessHours) {
			elasticBeanstalkActionService.suspendEnvironment(environment, autoScalingGroupName);
		// Verify if environment is already running
		} else if (!Constants.ENV_HEALTH_GREEN.equals(environment.getHealth())) {
			elasticBeanstalkActionService.resumeEnvironment(environment, autoScalingGroupName);
		}
	}

	private boolean autoSuspensionEnabled(DescribeTagsResult describeTagsResult) {
		if (CollectionUtils.isEmpty(tagFilter)) {
			return true;
		} else if (describeTagsResult == null || CollectionUtils.isEmpty(describeTagsResult.getTags())) {
			return false;
		}
		return describeTagsResult.getTags().stream().filter(tagDescription -> this.isTagValid(tagDescription))
				.findFirst().isPresent();
	}
	
	private boolean isTagValid(TagDescription tagDescription) {
		return tagFilter.stream().filter(tag -> tag.getName().equals(tagDescription.getKey())
				&& tag.getValue().equals(tagDescription.getValue())).findFirst().isPresent();
	}
	
	private boolean offBusinessHours() {
		String offBusinessHourWindow = environment.getProperty(Constants.OFF_BUSINESS_HOUR_WINDOW);
		if (!StringUtils.hasText(offBusinessHourWindow)) {
			return false;
		}
		if (isOffDayOfWeek()) {
			return true;
		}
		String[] offBusinessHourWindows = offBusinessHourWindow.split(Constants.COMMA);
		return Stream.of(offBusinessHourWindows).filter(this::offBusinessHourWindow).findFirst().isPresent();
	}

	private boolean isOffDayOfWeek() {
		ZonedDateTime systemDateTime = LocalDateTime.now().atZone(ZoneId.systemDefault());
		ZonedDateTime targetZonedDateTime = systemDateTime.withZoneSameInstant(getOffBusinessHourTimezone());

		String offDayOfWeeks = environment.getProperty(Constants.OFF_BUSINESS_DAY_OF_WEEK);
		if (!StringUtils.hasText(offDayOfWeeks)) {
			return false;
		}
		String[] offDayOfWeek = offDayOfWeeks.split(Constants.COMMA);
		return Stream.of(offDayOfWeek).filter(e -> targetZonedDateTime.getDayOfWeek() == DayOfWeek.valueOf(e))
				.findFirst().isPresent();
	}

	private ZoneId getOffBusinessHourTimezone() {
		return StringUtils.hasText(environment.getProperty(Constants.OFF_BUSINESS_HOUR_TIMEZONE))
				? ZoneId.of(environment.getProperty(Constants.OFF_BUSINESS_HOUR_TIMEZONE))
				: ZoneId.systemDefault();
	}

	private boolean offBusinessHourWindow(String window) {
		ZonedDateTime systemDateTime = LocalDateTime.now().atZone(ZoneId.systemDefault());
		ZonedDateTime targetZonedDateTime = systemDateTime.withZoneSameInstant(getOffBusinessHourTimezone());
		String[] startAndEndHours = window.split(Constants.HYPEN);
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
		return (localTime.equals(LocalTime.parse(startAndEndHours[0]))
				|| localTime.isAfter(LocalTime.parse(startAndEndHours[0])))
				&& (localTime.equals(LocalTime.parse(startAndEndHours[1]))
						|| localTime.isBefore(LocalTime.parse(startAndEndHours[1])));
	}

	private String getAutoScalingGroupName(DescribeEnvironmentResourcesResult describeEnvironmentResourcesResult) {
		return describeEnvironmentResourcesResult.getEnvironmentResources().getAutoScalingGroups().get(0).getName();
	}

	private <T> T convertToValue(String content, Class<T> clazz) {
		try {
			return mapper.readValue(content, clazz);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private <T> T convertToValue(String content, TypeReference<T> type) {
		try {
			return mapper.readValue(content, type);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}