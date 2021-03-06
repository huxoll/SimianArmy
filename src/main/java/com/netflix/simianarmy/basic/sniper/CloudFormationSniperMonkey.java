package com.netflix.simianarmy.basic.sniper;

import com.netflix.simianarmy.sniper.SniperCrawler.InstanceGroup;

/**
 * The Class CloudFormationSniperMonkey. Strips out the random string generated by the CloudFormation api in
 * the instance group name of the ASG we want to kill instances on
 */
public class CloudFormationSniperMonkey extends BasicSniperMonkey {

    /**
     * Instantiates a new cloud formation sniper monkey.
     * @param ctx
     *            the ctx
     */
    public CloudFormationSniperMonkey(Context ctx) {
        super(ctx);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isGroupEnabled(InstanceGroup group) {
        InstanceGroup noSuffixGroup = noSuffixInstanceGroup(group);
        return super.isGroupEnabled(noSuffixGroup);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isMaxTerminationCountExceeded(InstanceGroup group) {
        InstanceGroup noSuffixGroup = noSuffixInstanceGroup(group);
        return super.isMaxTerminationCountExceeded(noSuffixGroup);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected double getEffectiveProbability(InstanceGroup group) {
        InstanceGroup noSuffixGroup = noSuffixInstanceGroup(group);
        if (!super.isGroupEnabled(noSuffixGroup)) {
            return 0;
        }
        return getEffectiveProbabilityFromCfg(noSuffixGroup);
    }

    /**
     * Returns the lastOptInTimeInMilliseconds parameter for a group omitting the
     * randomly generated suffix.
     */
    @Override
    protected long getLastOptInMilliseconds(InstanceGroup group) {
        InstanceGroup noSuffixGroup = noSuffixInstanceGroup(group);
        return super.getLastOptInMilliseconds(noSuffixGroup);
    }

    /**
     * Handle email notifications for no suffix instance groups.
     */
    @Override
    public void sendTerminationNotification(InstanceGroup group, String instance) {
        InstanceGroup noSuffixGroup = noSuffixInstanceGroup(group);
        super.sendTerminationNotification(noSuffixGroup, instance);
    }

    /**
     * Return a copy of the instance group removing the randomly generated suffix from
     * its name.
     */
    public InstanceGroup noSuffixInstanceGroup(InstanceGroup group) {
        String newName = group.name().replaceAll("(-)([^-]*$)", "");
        InstanceGroup noSuffixGroup = group.copyAs(newName);
        return noSuffixGroup;
    }
}
