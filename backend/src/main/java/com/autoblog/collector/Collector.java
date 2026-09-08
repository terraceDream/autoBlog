package com.autoblog.collector;

import com.autoblog.Models.*;
import java.util.List;

/** Add a Spring bean implementing this interface to extend collection providers. */
public interface Collector {
    ModuleInfo info();
    List<Candidate> collect(Topic topic, Source source) throws Exception;
    record ModuleInfo(String type, String name, String description, boolean ready, String setup) {}
}
