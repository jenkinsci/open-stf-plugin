# open-stf-plugin

# About

Provides some features for Android development and testing with [The OpenSTF](http://openstf.io/) during Jenkins builds, including:

* Reserves and connect a STF device before build, and release it after use.
* Provides the matrix axis for the STF device attribute.

# Dependent plugins

* [Android Emulator Plugin](https://wiki.jenkins-ci.org/display/JENKINS/Android+Emulator+Plugin)
* [jQuery Plugin](https://wiki.jenkins-ci.org/display/JENKINS/jQuery+Plugin)

# Requirements

Jenkins [version 2.32](https://jenkins.io/changelog/#v2.32) or newer is required.<br />
Open STF [verion 2.0](https://github.com/openstf/stf) or later.

## Built With

* [Maven](https://maven.apache.org/) - Dependency Management

## Release History

* 1.0.9

  * ReFix for compatibility with JEP-200 (Incompatible change!)

* 1.0.8

  * Fix for compatibility with JEP-200 - Stop trying to serialize deviceCondition.

* 1.0.7

  * Fix for compatibility with [android-emulator v3.0](https://wiki.jenkins-ci.org/display/JENKINS/Android+Emulator+Plugin)

* 1.0.6

  * Added filter by battery remaining.

* 1.0.5

  * Fix Null Pointer error when waiting for device connection

* 1.0.4

  * Add a option about the SSL certificate vetification.(ignore errors or not) [JENKINS-39276](https://issues.jenkins-ci.org/browse/JENKINS-39276)
  * Add the 'abi' parameter as a device condition. [JENKINS-39277](https://issues.jenkins-ci.org/browse/JENKINS-39277)

* 1.0.3

  * Add feature about marking the device information on build.

* 1.0.2

  * Improve parameter validation.

* 1.0.1

  * Bug fix about jQuery confliction.

* 1.0.0
  * Initial release
