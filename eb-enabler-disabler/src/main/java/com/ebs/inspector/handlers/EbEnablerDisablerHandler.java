package com.ebs.inspector.handlers;

import org.springframework.cloud.function.adapter.aws.SpringBootRequestHandler;

import com.amazonaws.services.sqs.model.Message;

public class EbEnablerDisablerHandler extends SpringBootRequestHandler<Message, String> {
}