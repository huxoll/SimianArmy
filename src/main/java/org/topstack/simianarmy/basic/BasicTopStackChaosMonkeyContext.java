/*
 * TopStack (c) Copyright 2012-2013 Transcend Computing, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.topstack.simianarmy.basic;

import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.simpledb.AmazonSimpleDB;
import com.netflix.simianarmy.MonkeyConfiguration;
import com.netflix.simianarmy.basic.BasicChaosMonkeyContext;
import com.netflix.simianarmy.client.aws.AWSClient;

/**
* The Class BasicSimianArmyContext.
*/

/**
 * @author jgardner
 *
 */
public class BasicTopStackChaosMonkeyContext extends BasicChaosMonkeyContext {

   private String autoScaleUrl;

   private String computeUrl;

   private String loadBalanceUrl;

   /** protected constructor as the Shell is meant to be subclassed. */
   public BasicTopStackChaosMonkeyContext() {
       super();
       replaceRecorder(configuration());
       autoScaleUrl = configuration().getStr("simianarmy.client.topstack.autoscale");
       computeUrl = configuration().getStr("simianarmy.client.topstack.compute");
       loadBalanceUrl = configuration().getStr("simianarmy.client.topstack.slb");
   }

   private void replaceRecorder(MonkeyConfiguration configuration) {
       setRecorder(new SimplerDbRecorder(configuration));
   }

   /**
    * Create the specific client within passed region, using the appropriate AWS credentials provider.
    * @param clientRegion
    */
   protected void createClient(String clientRegion) {
       super.awsClient();
       AWSOverrideClient client = new AWSOverrideClient(clientRegion);
       setCloudClient(client);
   }

   /** {@inheritDoc} */
   /*
   @Override
   public MonkeyRecorder recorder() {
       return null; //return recorder;
   }*/

   /**
    * The Netflix AWS client almost works for TopStack, but generates fixed
    * URLs based on Amazon; this injects configured URL for TopStack.
    *
    * @author jgardner
    *
    */
   private class AWSOverrideClient extends AWSClient {

    /**
     * @param region
     */
    public AWSOverrideClient(String region) {
        super(region);
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.client.aws.AWSClient#ec2Client()
     */
    @Override
    protected AmazonEC2 ec2Client() {
        AmazonEC2 ec2Client = super.ec2Client();
        ec2Client.setEndpoint(computeUrl);
        return ec2Client;
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.client.aws.AWSClient#asgClient()
     */
    @Override
    protected AmazonAutoScalingClient asgClient() {
        AmazonAutoScalingClient asgClient = super.asgClient();
        asgClient.setEndpoint(autoScaleUrl);
        return asgClient;
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.client.aws.AWSClient#elbClient()
     */
    @Override
    protected AmazonElasticLoadBalancingClient elbClient() {
        AmazonElasticLoadBalancingClient elbClient = super.elbClient();
        elbClient.setEndpoint(loadBalanceUrl);
        return elbClient;
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.client.aws.AWSClient#sdbClient()
     */
    @Override
    public AmazonSimpleDB sdbClient() {
        AmazonSimpleDB sdbClient = super.sdbClient();
        return sdbClient;
    }

   }
}
