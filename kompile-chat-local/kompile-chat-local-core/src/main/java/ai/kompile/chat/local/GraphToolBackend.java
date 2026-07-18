package ai.kompile.chat.local;

/**
 * Backend-neutral graph tool contract used by the chat loop.
 *
 * <p>Desktop uses the in-process Java graph implementation while mobile uses the
 * AOT C ABI through JavaCPP. Both implementations expose the same catalog and
 * JSON dispatch semantics.</p>
 */
public interface GraphToolBackend extends AutoCloseable {

    /** Return the available graph tools as a JSON array. */
    String catalogJson();

    /** Execute one graph tool and return its JSON result. */
    String execute(String toolName, String argsJson);

    /** Release all graph sessions and native/runtime resources. */
    @Override
    void close();
}
