package com.warhex.er.generator;

/**
 * @deprecated Renamed to {@link FaceIdlGen}. This shim delegates to {@link FaceIdlGen#main}
 *             for backward compatibility with any scripts calling {@code com.warhex.er.generator.Main}.
 *             Git-remove this file once all callers have been updated.
 */
@Deprecated
public class Main {

    public static void main(String[] args) {
        FaceIdlGen.main(args);
    }
}
