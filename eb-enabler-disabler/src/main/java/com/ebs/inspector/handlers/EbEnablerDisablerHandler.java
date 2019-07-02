package com.ebs.inspector.handlers;

import org.springframework.cloud.function.adapter.aws.SpringBootRequestHandler;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;

public class EbEnablerDisablerHandler extends SpringBootRequestHandler<SQSEvent, String> {
}