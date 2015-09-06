package rs.examples.counter;

import rs.core.Subject;
import rs.core.javaapi.JServiceCell;
import rs.core.stream.DictionaryMapStreamState.Dictionary;
import rs.core.stream.ListStreamState;
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

        topicToStreamRef("string", "ticker");
        topicToStreamRef("set", "counterset");
        topicToStreamRef("list", "list");
        topicToStreamRef("map", "map");

        scheduleOnceToSelf(1000, "tick");
    }
}
