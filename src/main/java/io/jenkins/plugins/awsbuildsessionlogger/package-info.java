/**
 * AWS Build Session Logger Plugin.
 *
 * <p>Gives every AWS call a Jenkins build makes a build-attributable STS session
 * name, {@code jk-<job>-<build>}, by decorating the agent's own AWS configuration.
 * See the README and {@code docs/} for the design.
 */
package io.jenkins.plugins.awsbuildsessionlogger;
