/*
 * Copyright 2014-15 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rs.examples.counter;

import rs.core.Subject;
import rs.core.javaapi.JServiceCell;
import rs.core.stream.DictionaryMapStreamState.Dictionary;
import scala.Option;

import java.util.HashSet;
import java.util.LinkedList;

public class JCounterService extends JServiceCell {

    public JCounterService(String id) {
        super(id);
    }

    @Override
    public void serviceInitialization() {
        onMessage(String.class, new MessageCallback<String>() {

            private Dictionary dict = new Dictionary(new String[]{"a", "b", "c"});
            private int counter = 0;

            @Override
            public void handle(String v) {
                streamSetAdd("counterset", "jset-" + counter);
                streamSetRemove("counterset", "jset-" + (counter - 20));
                streamString("ticker", "jhello-" + (counter++));
                streamListAdd("list", -1, "jlist-" + counter);
                streamMapSnapshot("map", new Object[]{"a-" + counter, "b-" + (counter + 100), "c"}, dict);
                scheduleOnceToSelf(5000, "tick");
            }
        });

        onStreamActive("counterset", new StreamStateCallback() {

            @Override
            public void handle(String v) {
                streamSetSnapshot(v, new HashSet<String>(), true);
            }
        });
        onStreamActive("list", new StreamStateCallback() {

            @Override
            public void handle(String v) {
                streamListSnapshot(v, new LinkedList<String>(), 10, listEvictionFromHead());
            }
        });

        onSignalForTopic("signal", new SignalCallback() {
            @Override
            public Option<SignalResponse> handle(Subject subj, Object payload) {
                return success(payload + " - well done ");
            }
        });

        onTopicSubscription("string", "ticker");
        onTopicSubscription("set", "counterset");
        onTopicSubscription("list", "list");
        onTopicSubscription("map", "map");

        scheduleOnceToSelf(1000, "tick");
    }
}
