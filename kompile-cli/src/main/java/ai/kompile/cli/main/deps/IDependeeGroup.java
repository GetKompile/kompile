package ai.kompile.cli.main.deps;

import java.util.Collection;

public interface IDependeeGroup<T> {
    long getId();

    Collection<T> getCollection();

}