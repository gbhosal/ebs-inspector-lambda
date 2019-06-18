package com.ebs.inspector.function;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.amazonaws.services.sqs.model.Message;

/**
 * @author Ganesh Bhosale
 */
@Component("EbEnablerDisablerFunction")
public class EbEnablerDisablerFunction implements Function<Message, String> {
	private static Logger LOGGER = LoggerFactory.getLogger(EbEnablerDisablerFunction.class);
	
	/** 
	 * Entry point of function business logic
	 */
	@Override
	public String apply(Message sqsMessage) {
		LOGGER.info("SQS Message = {}", sqsMessage);
		
		return "SUCCESS";
	}
}