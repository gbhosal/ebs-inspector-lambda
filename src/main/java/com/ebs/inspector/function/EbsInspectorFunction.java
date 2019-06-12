package com.ebs.inspector.function;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.stereotype.Component;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

@Component("EbsInspectorFunction")
public class EbsInspectorFunction implements Function<ScheduledEvent, Message<String>>{
	private static Logger LOGGER = LoggerFactory.getLogger(EbsInspectorFunction.class);
	
	@Override
	public Message<String> apply(ScheduledEvent scheduleEvent) {
		LOGGER.info("ScheduledEvent = {}", scheduleEvent);
		return new GenericMessage<String>("SUCCESS");
	}
}
