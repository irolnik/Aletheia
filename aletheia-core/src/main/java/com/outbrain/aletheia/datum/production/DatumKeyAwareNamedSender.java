package com.outbrain.aletheia.datum.production;

/**
 * A {@link Sender} that has an alias.
 */
public interface DatumKeyAwareNamedSender<T> extends Named, DatumKeyAwareSender<T> {
  String getName();
}