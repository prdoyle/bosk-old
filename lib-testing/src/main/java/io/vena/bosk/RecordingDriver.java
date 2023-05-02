package io.vena.bosk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Value;

import static java.util.Collections.unmodifiableList;

public class RecordingDriver<R extends Entity> implements BoskDriver<R> {
	protected final List<Event> events = new ArrayList<>();
	public final R initialRoot;

	public RecordingDriver(R initialRoot) {
		this.initialRoot = initialRoot;
	}

	@Value
	public static class Event {
		String name;
		List<Object> arguments;

		public static Event of(String name, Object... arguments) {
			return new Event(name, Arrays.asList(arguments));
		}
	}

	public List<Event> events() {
		return unmodifiableList(events);
	}

	@Override
	public R initialRoot(Type rootType) {
		return initialRoot;
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		events.add(Event.of("submitReplacement", target, newValue));
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		events.add(Event.of("submitConditionalReplacement", target, newValue, precondition, requiredValue));
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		events.add(Event.of("submitInitialization", target, newValue));
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		events.add(Event.of("submitDeletion", target));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		events.add(Event.of("submitConditionalDeletion", target, precondition, requiredValue));
	}

	@Override
	public void flush() {
		events.add(Event.of("flush"));
	}
}
