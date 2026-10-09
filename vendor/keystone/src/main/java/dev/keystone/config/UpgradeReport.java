package dev.keystone.config;

import java.util.ArrayList;
import java.util.List;

/** What an upgrade changed, for the log line. */
public final class UpgradeReport {

    final List<String> added = new ArrayList<>();
    final List<String> removed = new ArrayList<>();
    final List<String> keptMismatched = new ArrayList<>();

    public List<String> added() {
        return added;
    }

    public List<String> removed() {
        return removed;
    }

    /** Keys whose user value has a different shape than the default and was kept. */
    public List<String> keptMismatched() {
        return keptMismatched;
    }

    public boolean changed() {
        return !added.isEmpty() || !removed.isEmpty();
    }
}
