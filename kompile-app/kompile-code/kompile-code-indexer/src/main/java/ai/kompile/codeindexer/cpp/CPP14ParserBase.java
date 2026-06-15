package ai.kompile.codeindexer.cpp;

import org.antlr.v4.runtime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CPP14ParserBase extends Parser
{
    private static final Logger log = LoggerFactory.getLogger(CPP14ParserBase.class);

    protected CPP14ParserBase(TokenStream input)
    {
        super(input);
    }

    protected boolean IsPureSpecifierAllowed()
    {
        try
        {
            var x = this._ctx; // memberDeclarator
            var c = x.getChild(0).getChild(0);
            var c2 = c.getChild(0);
            var p = c2.getChild(1);
            if (p == null) return false;
            return (p instanceof CPP14Parser.ParametersAndQualifiersContext);
        }
        catch (Exception e)
        {
            log.debug("IsPureSpecifierAllowed check failed (treating as not pure): {}", e.getMessage());
        }
        return false;
    }
}
