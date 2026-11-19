package jobagent;

import com.embabel.agent.core.hitl.FormBindingRequest;
import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext;
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext;
import com.embabel.agent.core.persistence.BlackboardEntrySerializer;
import com.embabel.agent.core.persistence.SerializedBlackboardValue;
import com.embabel.ux.form.SimpleFormGenerator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Lets a paused run be snapshotted while it waits for a recruiter's answer.
 *
 * Embabel 1.5.4's default Jackson serializer writes a {@code FormBindingRequest} that it then
 * cannot read back (the JSON has "payload", the constructor wants "form"), so restoring a
 * waiting process fails. This serializer stores just the question and the answer type, and
 * rebuilds the form from them with Embabel's own form generator.
 */
@Component
public class FormRequestSerializer implements BlackboardEntrySerializer {

    private static final String TYPE = FormBindingRequest.class.getName();
    private static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    @Override
    public boolean supportsSerialization(Object value) {
        return value instanceof FormBindingRequest<?>;
    }

    @Override
    public boolean supportsDeserialization(SerializedBlackboardValue value) {
        return TYPE.equals(value.getTypeName());
    }

    @Override
    public SerializedBlackboardValue serialize(Object value, BlackboardEntrySerializationContext context) {
        FormBindingRequest<?> request = (FormBindingRequest<?>) value;
        // Two lines: the answer class name, then the question shown to the human.
        String text = request.getOutputClass().getName() + "\n" + request.getPayload().getTitle();
        return new SerializedBlackboardValue(TYPE, CONTENT_TYPE, text.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    @Override
    public Object deserialize(SerializedBlackboardValue value, BlackboardEntryDeserializationContext context) {
        String[] lines = new String(value.getPayload(), StandardCharsets.UTF_8).split("\n", 2);
        try {
            Class<?> outputClass = Class.forName(lines[0]);
            var form = SimpleFormGenerator.INSTANCE.generateForm(outputClass, lines[1]);
            return new FormBindingRequest<>(form, outputClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot restore form request: " + lines[0], e);
        }
    }
}
