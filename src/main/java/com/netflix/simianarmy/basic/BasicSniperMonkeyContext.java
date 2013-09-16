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
package com.netflix.simianarmy.basic;

import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient;
import com.netflix.simianarmy.MonkeyConfiguration;
import com.netflix.simianarmy.basic.sniper.BasicSniperEmailNotifier;
import com.netflix.simianarmy.basic.sniper.BasicSniperInstanceSelector;
import com.netflix.simianarmy.sniper.SniperCrawler;
import com.netflix.simianarmy.sniper.SniperEmailNotifier;
import com.netflix.simianarmy.sniper.SniperInstanceSelector;
import com.netflix.simianarmy.sniper.SniperMonkey;
import com.netflix.simianarmy.client.aws.sniper.ASGSniperCrawler;

/**
 * The Class BasicContext. This provide the basic context needed for the Sniper Monkey to run. It will configure
 * the Sniper Monkey based on a simianarmy.properties file and sniper.properties. The properties file can be
 * overridden with -Dsimianarmy.properties=/path/to/my.properties
 */
public class BasicSniperMonkeyContext extends BasicSimianArmyContext implements SniperMonkey.Context {

    /** The crawler. */
    private SniperCrawler crawler;

    /** The selector. */
    private SniperInstanceSelector selector;

    /** The sniper email notifier. */
    private SniperEmailNotifier sniperEmailNotifier;

    /**
     * Instantiates a new basic context.
     */
    public BasicSniperMonkeyContext() {
        super("simianarmy.properties", "client.properties", "sniper.properties");
        setSniperCrawler(new ASGSniperCrawler(awsClient()));
        setSniperInstanceSelector(new BasicSniperInstanceSelector());
        MonkeyConfiguration cfg = configuration();
        setSniperEmailNotifier(new BasicSniperEmailNotifier(cfg, new AmazonSimpleEmailServiceClient(), null));
    }

    /** {@inheritDoc} */
    @Override
    public SniperCrawler sniperCrawler() {
        return crawler;
    }

    /**
     * Sets the sniper crawler.
     *
     * @param sniperCrawler
     *            the new sniper crawler
     */
    protected void setSniperCrawler(SniperCrawler sniperCrawler) {
        this.crawler = sniperCrawler;
    }

    /** {@inheritDoc} */
    @Override
    public SniperInstanceSelector sniperInstanceSelector() {
        return selector;
    }

    /**
     * Sets the sniper instance selector.
     *
     * @param sniperInstanceSelector
     *            the new sniper instance selector
     */
    protected void setSniperInstanceSelector(SniperInstanceSelector sniperInstanceSelector) {
        this.selector = sniperInstanceSelector;
    }

    @Override
    public SniperEmailNotifier sniperEmailNotifier() {
        return sniperEmailNotifier;
    }

    /**
     * Sets the sniper email notifier.
     *
     * @param notifier
     *            the sniper email notifier
     */
    protected void setSniperEmailNotifier(SniperEmailNotifier notifier) {
        this.sniperEmailNotifier = notifier;
    }
}
