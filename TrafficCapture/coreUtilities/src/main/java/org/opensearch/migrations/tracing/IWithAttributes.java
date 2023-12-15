package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;

import java.util.ArrayList;

public interface IWithAttributes {
    IWithAttributes getEnclosingScope();

    Span getCurrentSpan();

    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder;
    }

    default Attributes getPopulatedAttributes() {
        return getPopulatedAttributesBuilder().build();
    }

    default AttributesBuilder getPopulatedAttributesBuilder() {
        var currentObj = this;
        var stack = new ArrayList<IWithAttributes>();
        var builder = Attributes.builder();
        while (currentObj != null) {
            stack.add(currentObj);
            currentObj = currentObj.getEnclosingScope();
        }
        // reverse the order so that the lowest attribute scopes will overwrite the upper ones if there were conflicts
        for (int i=stack.size()-1; i>=0; --i) {
            builder = stack.get(i).fillAttributes(builder);
        }
        return builder;
    }
}
