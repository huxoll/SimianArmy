/*
 *
 *  Copyright 2012 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.simianarmy.basic.sniper;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.netflix.simianarmy.FeatureNotEnabledException;
import com.netflix.simianarmy.InstanceGroupNotFoundException;
import com.netflix.simianarmy.MonkeyCalendar;
import com.netflix.simianarmy.MonkeyConfiguration;
import com.netflix.simianarmy.MonkeyRecorder.Event;
import com.netflix.simianarmy.NotFoundException;
import com.netflix.simianarmy.sniper.SniperCrawler.InstanceGroup;
import com.netflix.simianarmy.sniper.SniperEmailNotifier;
import com.netflix.simianarmy.sniper.SniperMonkey;

/**
 * The Class BasicSniperMonkey.
 */
public class BasicSniperMonkey extends SniperMonkey {

    /** The Constant LOGGER. */
    private static final Logger LOGGER = LoggerFactory.getLogger(BasicSniperMonkey.class);

    /** The Constant NS. */
    private static final String NS = "simianarmy.sniper.";
    
    private String user;

    private String privateKey;

    private String process;

    /** The cfg. */
    private final MonkeyConfiguration cfg;

    /** The runs per day. */
    private final long runsPerDay;

    /** The minimum value of the maxTerminationCountPerday property to be considered non-zero. **/
    private static final double MIN_MAX_TERMINATION_COUNT_PER_DAY = 0.001;

    private final MonkeyCalendar monkeyCalendar;

    // When a mandatory termination is triggered due to the minimum termination limit is breached,
    // the value below is used as the termination probability.
    private static final double DEFAULT_MANDATORY_TERMINATION_PROBABILITY = 0.5;

    /**
     * Instantiates a new basic sniper monkey.
     * @param ctx
     *            the ctx
     */
    public BasicSniperMonkey(SniperMonkey.Context ctx) {
        super(ctx);

        this.cfg = ctx.configuration();
        this.monkeyCalendar = ctx.calendar();

        Calendar open = monkeyCalendar.now();
        Calendar close = monkeyCalendar.now();
        open.set(Calendar.HOUR, monkeyCalendar.openHour());
        close.set(Calendar.HOUR, monkeyCalendar.closeHour());

        TimeUnit freqUnit = ctx.scheduler().frequencyUnit();
        long units = freqUnit.convert(close.getTimeInMillis() - open.getTimeInMillis(), TimeUnit.MILLISECONDS);
        runsPerDay = units / ctx.scheduler().frequency();
        
        privateKey = this.cfg.getStr("simianarmy.sniper.privateKey");
        user = this.cfg.getStr("simianarmy.sniper.user");
        process = this.cfg.getStr("simianarmy.sniper.process");
    }

    /** {@inheritDoc} */
    @Override
    public void doMonkeyBusiness() {
        context().resetEventReport();
        cfg.reload();
        if (!isSniperMonkeyEnabled()) {
            return;
        }
        for (InstanceGroup group : context().sniperCrawler().groups()) {
            if (isGroupEnabled(group)) {
                if (isMaxTerminationCountExceeded(group)) {
                    continue;
                }
                double prob = getEffectiveProbability(group);
                Collection<String> instances = context().sniperInstanceSelector().select(group, prob / runsPerDay);
                for (String inst : instances) {
                	killProcess(group, inst);
                    terminateInstance(group, inst);
                }
            }
        }
    }

    @Override
    public Event terminateNow(String type, String name)
            throws FeatureNotEnabledException, InstanceGroupNotFoundException {
        Validate.notNull(type);
        Validate.notNull(name);
        cfg.reload(name);
        if (!isSniperMonkeyEnabled()) {
            String msg = String.format("Sniper monkey is not enabled for group %s [type %s]",
                    name, type);
            LOGGER.info(msg);
            throw new FeatureNotEnabledException(msg);
        }
        String prop = NS + "terminateOndemand.enabled";
        if (cfg.getBool(prop)) {
            InstanceGroup group = findInstanceGroup(type, name);
            if (group == null) {
                throw new InstanceGroupNotFoundException(type, name);
            }
            Collection<String> instances = context().sniperInstanceSelector().select(group, 1.0);
            Validate.isTrue(instances.size() <= 1);
            if (instances.size() == 1) {
                return terminateInstance(group, instances.iterator().next());
            } else {
                throw new NotFoundException(String.format("No instance is found in group %s [type %s]",
                        name, type));
            }
        } else {
            String msg = String.format("Group %s [type %s] does not allow on-demand termination, set %s=true",
                    name, type, prop);
            LOGGER.info(msg);
            throw new FeatureNotEnabledException(msg);
        }
    }

    private void reportEventForSummary(EventTypes eventType, InstanceGroup group, String instanceId) {
        context().reportEvent(createEvent(eventType, group, instanceId));
    }

    /**
     * Handle termination error. This has been abstracted so subclasses can decide to continue causing sniper if desired.
     *
     * @param instance
     *            the instance
     * @param e
     *            the exception
     */
    protected void handleTerminationError(String instance, Throwable e) {
        LOGGER.error("failed to terminate instance " + instance, e.getMessage());
        throw new RuntimeException("failed to terminate instance " + instance, e);
    }

    /** {@inheritDoc} */
    @Override
    public Event recordTermination(InstanceGroup group, String instance) {
        Event evt = context().recorder().newEvent(Type.SNIPER, EventTypes.SNIPER_TERMINATION, group.region(), instance);
        evt.addField("groupType", group.type().name());
        evt.addField("groupName", group.name());
        context().recorder().recordEvent(evt);
        return evt;
    }

    /** {@inheritDoc} */
    @Override
    public int getPreviousTerminationCount(InstanceGroup group, Date after) {
        Map<String, String> query = new HashMap<String, String>();
        query.put("groupType", group.type().name());
        query.put("groupName", group.name());
        List<Event> evts = context().recorder().findEvents(Type.SNIPER, EventTypes.SNIPER_TERMINATION, query, after);
        return evts.size();
    }

    private Event createEvent(EventTypes sniperTermination, InstanceGroup group, String instance) {
        Event evt = context().recorder().newEvent(Type.SNIPER, sniperTermination, group.region(), instance);
        evt.addField("groupType", group.type().name());
        evt.addField("groupName", group.name());
        return evt;
    }

    /**
     * Gets the effective probability value, returns 0 if the group is not enabled. Otherwise calls
     * getEffectiveProbability.
     * @param group
     * @return the effective probability value for the instance group
     */
    protected double getEffectiveProbability(InstanceGroup group) {
        if (!isGroupEnabled(group)) {
            return 0;
        }
        return getEffectiveProbabilityFromCfg(group);
    }

    /**
     * Gets the effective probability value when the monkey processes an instance group, it uses the following
     * logic in the order as listed below.
     *
     * 1) When minimum mandatory termination is enabled, a default non-zero probability is used for opted-in
     * groups, if a) the application has been opted in for the last mandatory termination window
     *        and b) there was no terminations in the last mandatory termination window
     * 2) Use the probability configured for the group type and name
     * 3) Use the probability configured for the group
     * 4) Use 1.0
     * @param group
     * @return double
     */
    protected double getEffectiveProbabilityFromCfg(InstanceGroup group) {
        String propName;
        if (cfg.getBool(NS + "mandatoryTermination.enabled")) {
            String mtwProp = NS + "mandatoryTermination.windowInDays";
            int mandatoryTerminationWindowInDays = (int) cfg.getNumOrElse(mtwProp, 0);
            if (mandatoryTerminationWindowInDays > 0
                    && noTerminationInLastWindow(group, mandatoryTerminationWindowInDays)) {
                double mandatoryProb = cfg.getNumOrElse(NS + "mandatoryTermination.defaultProbability",
                        DEFAULT_MANDATORY_TERMINATION_PROBABILITY);
                LOGGER.info("There has been no terminations for group {} [type {}] in the last {} days,"
                        + "setting the probability to {} for mandatory termination.",
                        new Object[]{group.name(), group.type(), mandatoryTerminationWindowInDays, mandatoryProb});
                return mandatoryProb;
            }
        }
        propName = "probability";
        String defaultProp = NS + group.type();
        String probProp = NS + group.type() + "." + group.name() + "." + propName;
        double prob = cfg.getNumOrElse(probProp, cfg.getNumOrElse(defaultProp + "." + propName, 1.0));
        LOGGER.info("Group {} [type {}] enabled [prob {}]", new Object[]{group.name(), group.type(), prob});
        return prob;
    }

    /**
     * Returns lastOptInTimeInMilliseconds from the .properties file.
     *
     * @param group
     * @return long
     */
    protected long getLastOptInMilliseconds(InstanceGroup group) {
        String prop = NS + group.type() + "." + group.name() + ".lastOptInTimeInMilliseconds";
        long lastOptInTimeInMilliseconds = (long) cfg.getNumOrElse(prop, -1);
        return lastOptInTimeInMilliseconds;
    }

    private boolean noTerminationInLastWindow(InstanceGroup group, int mandatoryTerminationWindowInDays) {
    long lastOptInTimeInMilliseconds = getLastOptInMilliseconds(group);
        if (lastOptInTimeInMilliseconds < 0) {
            return false;
        }

        Calendar windowStart = monkeyCalendar.now();
        windowStart.add(Calendar.DATE, -1 * mandatoryTerminationWindowInDays);

        // return true if the window start is after the last opt-in time and
        // there has been no termination since the window start
        if (windowStart.getTimeInMillis() > lastOptInTimeInMilliseconds
                && getPreviousTerminationCount(group, windowStart.getTime()) <= 0) {
            return true;
        }

        return false;
    }

    /**
     * Checks to see if the given instance group is enabled.
     * @param group
     * @return boolean
     */
    protected boolean isGroupEnabled(InstanceGroup group) {
        String prop = NS + group.type() + "." + group.name() + ".enabled";
        String defaultProp = NS + group.type() + ".enabled";
        if (cfg.getBoolOrElse(prop, cfg.getBool(defaultProp))) {
            return true;
        } else {
            LOGGER.info("Group {} [type {}] disabled, set {}=true or {}=true",
                    new Object[]{group.name(), group.type(), prop, defaultProp});
            return false;
        }
    }

    private boolean isSniperMonkeyEnabled() {
        String prop = NS + "enabled";
        if (cfg.getBoolOrElse(prop, true)) {
            return true;
        }
        LOGGER.info("SniperMonkey disabled, set {}=true", prop);
        return false;
    }

    private InstanceGroup findInstanceGroup(String type, String name) {
        // Calling context().sniperCrawler().groups(name) causes a new crawl to get
        // the up to date information for the group name.
        for (InstanceGroup group : context().sniperCrawler().groups(name)) {
            if (group.type().toString().equals(type) && group.name().equals(name)) {
                return group;
            }
        }
        LOGGER.warn("Failed to find instance group for type {} and name {}", type, name);
        return null;
    }

    private Event terminateInstance(InstanceGroup group, String inst) {
        Validate.notNull(group);
        Validate.notEmpty(inst);
        String prop = NS + "leashed";
        if (cfg.getBoolOrElse(prop, false)) {
            LOGGER.info("leashed SniperMonkey prevented from killing {} from group {} [{}], set {}=false",
                    new Object[]{inst, group.name(), group.type(), prop});
            reportEventForSummary(EventTypes.SNIPER_TERMINATION_SKIPPED, group, inst);
            return null;
        } else {
            try {
                Event evt = recordTermination(group, inst);
                sendTerminationNotification(group, inst);
                context().cloudClient().terminateInstance(inst);
                LOGGER.info("Terminated {} from group {} [{}]", new Object[]{inst, group.name(), group.type()});
                reportEventForSummary(EventTypes.SNIPER_TERMINATION, group, inst);
                return evt;
            } catch (NotFoundException e) {
                LOGGER.warn("Failed to terminate " + inst + ", it does not exist. Perhaps it was already terminated");
                reportEventForSummary(EventTypes.SNIPER_TERMINATION_SKIPPED, group, inst);
                return null;
            } catch (Exception e) {
                handleTerminationError(inst, e);
                reportEventForSummary(EventTypes.SNIPER_TERMINATION_SKIPPED, group, inst);
                return null;
            }
        }
    }
    
    private Event killProcess(InstanceGroup group, String inst) {
        Validate.notNull(group);
        Validate.notEmpty(inst);
        String prop = NS + "leashed";
        if (cfg.getBoolOrElse(prop, false)) {
            LOGGER.info("leashed SniperMonkey prevented from killing {} from group {} [{}], set {}=false",
                    new Object[]{inst, group.name(), group.type(), prop});
            reportEventForSummary(EventTypes.SNIPER_TERMINATION_SKIPPED, group, inst);
            return null;
        } else {
            try {
                Event evt = recordTermination(group, inst);
                sendTerminationNotification(group, inst);
                try {
        			JSch jsch = new JSch();

        			jsch.addIdentity(privateKey, "");
        			Session session = jsch.getSession(user,
        					inst, 22);

        			session.setPassword("");
        			java.util.Properties config = new java.util.Properties();
        			config.put("StrictHostKeyChecking", "no");
        			session.setConfig(config);

        			session.connect();

        			// String command="mkdir /data";
        			String command = "ps -ef | grep " + process + " | grep -v grep | awk '{print $2}' | xargs sudo kill -9";
        			String sudo_pass = null;

        			sudo_pass = "";

        			Channel channel = session.openChannel("exec");

        			((ChannelExec) channel).setPty(true);

        			// man sudo
        			// -S The -S (stdin) option causes sudo to read the password from
        			// the
        			// standard input instead of the terminal device.
        			// -p The -p (prompt) option allows you to override the default
        			// password prompt and use a custom one.
        			((ChannelExec) channel).setCommand("sudo -S -p '' " + command);

        			InputStream in = channel.getInputStream();
        			OutputStream out = channel.getOutputStream();
        			((ChannelExec) channel).setErrStream(System.err);

        			channel.connect();

        			out.write((sudo_pass + "\n").getBytes());
        			out.flush();

        			byte[] tmp = new byte[1024];
        			while (true) {
        				while (in.available() > 0) {
        					int i = in.read(tmp, 0, 1024);
        					if (i < 0)
        						break;
        					System.out.print(new String(tmp, 0, i));
        				}
        				if (channel.isClosed()) {
        					System.out.println("exit-status: "
        							+ channel.getExitStatus());
        					break;
        				}
        				try {
        					Thread.sleep(1000);
        				} catch (Exception ee) {
        				}
        			}
        			channel.disconnect();
        			session.disconnect();
        		} catch (Exception e) {
        			System.out.println(e);
        		}
                LOGGER.info("Terminated {} from group {} [{}]", new Object[]{inst, group.name(), group.type()});
                reportEventForSummary(EventTypes.SNIPER_TERMINATION, group, inst);
                return evt;
            } catch (NotFoundException e) {
                LOGGER.warn("Failed to terminate " + inst + ", it does not exist. Perhaps it was already terminated");
                reportEventForSummary(EventTypes.SNIPER_TERMINATION_SKIPPED, group, inst);
                return null;
            } catch (Exception e) {
                handleTerminationError(inst, e);
                reportEventForSummary(EventTypes.SNIPER_TERMINATION_SKIPPED, group, inst);
                return null;
            }
        }
    }

    /**
     * Checks to see if the maximum termination window has been exceeded.
     *
     * @param group
     * @return boolean
     */
    protected boolean isMaxTerminationCountExceeded(InstanceGroup group) {
        Validate.notNull(group);
        String propName = "maxTerminationsPerDay";
        String defaultProp = String.format("%s%s.%s", NS, group.type(), propName);
        String prop = String.format("%s%s.%s.%s", NS, group.type(), group.name(), propName);
        double maxTerminationsPerDay = cfg.getNumOrElse(prop, cfg.getNumOrElse(defaultProp, 1.0));
        if (maxTerminationsPerDay <= MIN_MAX_TERMINATION_COUNT_PER_DAY) {
            LOGGER.info("SniperMonkey is configured to not allow any killing from group {} [{}] "
                    + "with max daily count set as {}", new Object[]{group.name(), group.type(), prop});
            return true;
        } else {
            int daysBack = 1;
            int maxCount = (int) maxTerminationsPerDay;
            if (maxTerminationsPerDay < 1.0) {
                daysBack = (int) Math.ceil(1 / maxTerminationsPerDay);
                maxCount = 1;
            }
            Calendar after = monkeyCalendar.now();
            after.add(Calendar.DATE, -1 * daysBack);
            // Check if the group has exceeded the maximum terminations for the last period
            int terminationCount = getPreviousTerminationCount(group, after.getTime());
            if (terminationCount >= maxCount) {
                LOGGER.info("The count of terminations for group {} [{}] in the last {} days is {},"
                        + " equal or greater than the max count threshold {}",
                        new Object[]{group.name(), group.type(), daysBack, terminationCount, maxCount});
                return true;
            }
        }
        return false;
    }

    @Override
    public void sendTerminationNotification(InstanceGroup group, String instance) {
        String propEmailGlobalEnabled = "simianarmy.sniper.notification.global.enabled";
        String propEmailGroupEnabled = String.format("%s%s.%s.notification.enabled", NS, group.type(), group.name());

        SniperEmailNotifier notifier = context().sniperEmailNotifier();
        if (notifier == null) {
            String msg = "Sniper email notifier is not set.";
            LOGGER.error(msg);
            throw new RuntimeException(msg);
        }
        if (cfg.getBoolOrElse(propEmailGroupEnabled, false)) {
            notifier.sendTerminationNotification(group, instance);
        }
        if (cfg.getBoolOrElse(propEmailGlobalEnabled, false)) {
            notifier.sendTerminationGlobalNotification(group, instance);
        }
    }
}