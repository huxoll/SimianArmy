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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.netflix.simianarmy.MonkeyRecorder;
import com.netflix.simianarmy.basic.BasicRecorderEvent;

/**
 * Replacement for SimpleDB on non-AWS: in-memory rotating buffer.
 * @author jgardner
 *
 */
public class InMemoryRecorder implements MonkeyRecorder {

    LinkedList<Event> events = new LinkedList<Event>();

    /**
     *
     */
    public InMemoryRecorder() {
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.MonkeyRecorder#newEvent(java.lang.Enum, java.lang.Enum, java.lang.String, java.lang.String)
     */
    @SuppressWarnings("rawtypes")
    @Override
    public Event newEvent(Enum monkeyType, Enum eventType, String region,
            String id) {
        return new BasicRecorderEvent(monkeyType, eventType, region, id);
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.MonkeyRecorder#recordEvent(com.netflix.simianarmy.MonkeyRecorder.Event)
     */
    @Override
    public void recordEvent(Event evt) {
        events.add(evt);
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.MonkeyRecorder#findEvents(java.util.Map, java.util.Date)
     */
    @Override
    public List<Event> findEvents(Map<String, String> query, Date after) {
        List<Event> foundEvents = new ArrayList<Event>();
        for (Event evt : events) {
            if (after != null && after.before(evt.eventTime())) {
                continue;
            }
            for (Map.Entry<String, String> pair : query.entrySet()) {
                if (pair.getKey().equals("id")) {
                    if (! evt.id().equals(pair.getValue())) {
                        continue;
                    }
                }
                if (pair.getKey().equals("monkeyType")) {
                    if (! evt.monkeyType().equals(pair.getValue())) {
                        continue;
                    }
                }
                if (pair.getKey().equals("eventType")) {
                    if (! evt.eventType().equals(pair.getValue())) {
                        continue;
                    }
                }
            }
            foundEvents.add(evt);
        }
        return foundEvents;
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.MonkeyRecorder#findEvents(java.lang.Enum, java.util.Map, java.util.Date)
     */
    @SuppressWarnings("rawtypes")
    @Override
    public List<Event> findEvents(Enum monkeyType, Map<String, String> query,
            Date after) {
        Map<String, String> copy = new LinkedHashMap<String, String>(query);
        copy.put("monkeyType", monkeyType.name());
        return findEvents(copy, after);
    }

    /* (non-Javadoc)
     * @see com.netflix.simianarmy.MonkeyRecorder#findEvents(java.lang.Enum, java.lang.Enum, java.util.Map, java.util.Date)
     */
    @Override
    public List<Event> findEvents(Enum monkeyType, Enum eventType,
            Map<String, String> query, Date after) {
        Map<String, String> copy = new LinkedHashMap<String, String>(query);
        copy.put("monkeyType", monkeyType.name());
        copy.put("eventType", eventType.name());
        return findEvents(copy, after);
    }

}
