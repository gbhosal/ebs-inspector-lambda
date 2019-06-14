# ebs-inspector-lambda
  This lambda code will inspect Elastic Beanstalk environments in the account and will suspend the environments if it falls outside of working hours and bring them back up during working hours, resulting significant saving to company.

EBS (Elastic Beanstalk) Inspector lambda is written on Java 8 and on one of the very popular Spring project(aka. module) called - Spring Cloud Function, which leverages AWS SDK for Java to manage the AWS resources.

## How does it work?
  Lambda fetches the list of all of the elastic beanstalk (EBS) environments from the AWS account in which lambda code is deployed, with the help of AWS SDK for Java, which internally uses the AWS REST API. Next, it makes a call to the list out the resources tied to EBS environment to find out Autoscaling group name, which subsequently used to get the details of tags attached to Autoscaling group. If Autoscaling group has tag `AutoSuspend` as `true` then it would be suspended if lambda is executed off the working hours. If the labmda is executed during the active working hours and the environment is in the suspended state then it would be put back in the service. Lambda wisely skips a few service calls depending upon when it is executed and what is the current status of EBS environment it is processing. To suspend the environment, lambda sets autoscaling groups's `min` and `desired` values as 0 so that EC2 instances managed by autoscaling group are terminated. Please note that when EBS environment is in suspeneded status, you will still pay for Elastic Load Balancer (ELB) associated with it.

> Actions are performed on the EBS environments that are tagged with `AutoSuspend`=`true`.
  
## Lambda configuration
  Lambda needs to know which elastic beanstalk environments can be suspended during off working hours because for whatever reason, it is possible that you would want to keep the few services running 24x7 in the test environments. Taking this requirement into account, lambda is designed to suspend the elastic beanstalk environments that are tagged with `AutoSuspend` as `true`.
  
### Environment variables
  The very first thing that this function does, is to verify if current time is outside of the working hours and for that, lambda needs to know what are your off working hours. This can be defined using the environment variable named `OFF_BUSINESS_HOUR_WINDOW`. You can configure multiple off business hours windows seperate by comma (",").
  
  Say your company's working hours are 8AM-5PM, then you can configure `OFF_BUSINESS_HOUR_WINDOW` as "00:00-8:00,17:00-23:59". Start and End time windows are seperated by hypen ("-") and they are in format of HH:MM where HH can be from 00-23 and MM can be from 00-59.
   
  `OFF_BUSINESS_HOUR_WINDOW` is relative to timezone. If you had a bit of experience with the lambda, you would know that system default timezone where Lambda run is GMT and configuring `OFF_BUSINESS_HOUR_WINDOW` isn't convenient option because GMT offset changes as per daylight saving hours. You are fortunate if you don't have to deal with daylight saving etc. but that's not the case with most of the usecases. Lambda is designed to accept timezone value that you want to use it for `OFF_BUSINESS_HOUR_WINDOW`. You can configure it through environment variable `OFF_BUSINESS_HOUR_TIMEZONE`, which you can set it with Timezone IDs. Please [refer](https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html) for the list of valid Timezone IDs. 
  
  Say your off working hour time windows are valid in the Chicago timezone then set `OFF_BUSINESS_HOUR_TIMEZONE` as `America/Chicago`.
   
  This is good for Monday through Friday but wouldn't it be great if through some way, we can configure lambda to consider certain days such as Saturday and Sunday, as off days so the services will remain in the suspended status during these days. Yes, that's possible through environment variable `OFF_BUSINESS_DAY_OF_WEEK` and valid values are `MONDAY`,`TUESDAY`,`WEDNESDAY`,`THURSDAY`,`FRIDAY`,`SATURDAY`,`SUNDAY`. You can set multiple days seperated by comma (,).
   
  Say you want to consider Saturday and Sunday as off days then you can set environment variable `OFF_BUSINESS_DAY_OF_WEEK` with `SATURDAY,SUNDAY`. 

## Deployment
Easiest way to deploy entire resources stack is using [serverless.yml](serverless.yml). This is the configuration file required by [`serverless.com`](http://serverless.com) framework. If you are not familiar with this framework, you have been doing heavy-lifting all by yourself which isn't necessary.

Simply execute below command and it would deploy the stack of resources. 
Note - You may need to modify the tags as per your need. Parameter `awsAccountNo` is required to pass because it is part of S3 bucket I  configured. You can simply choose the S3 bucket of your choice in serverless.yml and if it is static, you don't have to pass anything as parameter. I prefer to use some pattern so I can maintain just one copy of serverless.yml irrespective of the environments that I am deploying it into.
```
$serverless deploy --awsAccountNo <Your AWS ACCOUNT NO>
```
If you want to create the resources using AWS Console then you need to know the below details.

### Platform
As stated earlier, this lambda is written on Java 8 so choose `java8` as runtime for the lambda function. You don't have run lambda in the one of your VPC. It wouldn't make difference whether you run it in the VPC or No VPC because all REST API calls are over internet. If you still chose to run it within VPC, ensure that selected subnet have enough IPs available because one of those IP addresses from private IP pool of subnet will be used by lambda.

### Lambda role permissions
IAM policy attached to lambda should typically look like as mentioned below.

    {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Action": [
                    "logs:CreateLogStream"
                ],
                "Resource": [
                    "arn:aws:logs:<AWS Region>:<AWS Account No>:log-group:/aws/lambda/<Lambda function name>:*"
                ],
                "Effect": "Allow"
            },
            {
                "Action": [
                    "logs:PutLogEvents"
                ],
                "Resource": [
                    "arn:aws:logs:<AWS Region>:<AWS Account No>:log-group:/aws/lambda/<Lambda function name>:*:*"
                ],
                "Effect": "Allow"
            },
            {
                "Action": [
                    "elasticbeanstalk:DescribeEnvironmentResources",
                    "elasticbeanstalk:DescribeEnvironments",
                    "autoscaling:DescribeAutoScalingGroups",
                    "autoscaling:DescribeTags",
                    "cloudformation:DescribeStacks",
                    "autoscaling:UpdateAutoScalingGroup"
                ],
                "Resource": [
                    "*"
                ],
                "Effect": "Allow"
            }
        ]
    }

### Schedule lambda to execute
If you want to fully automate, you need to trigger lambda by some means during off working hours and during active working hours so that elastic beanstalk environments can be suspended or put them back in the service depending upon when lambda was execute and the working hours configured.

# Shortcomings
1. Lambda can only run upto 15 minutes. If you have hundreds of beanstalk environments then this solution may not fit to your need. To achieve the scalability of your need, you may want to group the few environment details in the single message and put that in the SQS by lambda. We can configure another lambda that polls the message from SQS and process them one by one. 
2. If you have EBS environment whose autoscaling groups `min` or `desired` value is set to greater than one then they will not be restored when environment is put back in the service. Lambda hardcodes them to 1. If you really need to restore them back to their original state then you need to modify lambda code to store its state before modifying it. somewhere example - DynamoDB or ElastiCache and use them while environment is put back in the service.
