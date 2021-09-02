package ai.konduit.pipelinegenerator.main;

import org.graalvm.nativeimage.c.struct.CPointerTo;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CLongPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.SignedWord;

/**
 * A pointer to a pointer to a 64-bit C primitive value.
 *
 * @since 19.0
 */
@CPointerTo(CLongPointer.class)
public interface CLongPointerPointer extends PointerBase {

    /**
     * Reads the value at the pointer address.
     *
     * @since 19.0
     */
    CLongPointer read();

    /**
     * Reads the value of the array element with the specified index, treating the pointer as an
     * array of the C type.
     *
     * @since 19.0
     */
    CLongPointer read(int index);

    /**
     * Reads the value of the array element with the specified index, treating the pointer as an
     * array of the C type.
     *
     * @since 19.0
     */
    CIntPointer read(SignedWord index);

    /**
     * Writes the value at the pointer address.
     *
     * @since 19.0
     */
    void write(CLongPointer value);

    /**
     * Writes the value of the array element with the specified index, treating the pointer as an
     * array of the C type.
     *
     * @since 19.0
     */
    void write(int index, CLongPointer value);

    /**
     * Writes the value of the array element with the specified index, treating the pointer as an
     * array of the C type.
     *
     * @since 19.0
     */
    void write(SignedWord index, CLongPointer value);

    /**
     * Computes the address of the array element with the specified index, treating the pointer as
     * an array of the C type.
     *
     * @since 19.0
     */
    CLongPointer addressOf(int index);

    /**
     * Computes the address of the array element with the specified index, treating the pointer as
     * an array of the C type.
     *
     * @since 19.0
     */
    CLongPointer addressOf(SignedWord index);
}
