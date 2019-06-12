package com.ebs.inspector.handlers;

import org.springframework.cloud.function.adapter.aws.SpringBootRequestHandler;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

public class EbsInspectionHandler extends SpringBootRequestHandler<ScheduledEvent, String> {
}
