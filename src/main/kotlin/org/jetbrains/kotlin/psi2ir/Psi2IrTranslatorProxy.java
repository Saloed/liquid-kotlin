package org.jetbrains.kotlin.psi2ir;

import kotlin.jvm.functions.Function1;
import org.jetbrains.kotlin.config.LanguageVersionSettings;
import org.jetbrains.kotlin.ir.declarations.IrClass;
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource;

public class Psi2IrTranslatorProxy {

    public static Psi2IrTranslator create(
            LanguageVersionSettings languageVersionSettings,
            Psi2IrConfiguration psi2IrConfiguration,
            Function1<? super DeserializedContainerSource, ? extends IrClass> facadeClassGenerator
    ) {
        return new Psi2IrTranslator(
                languageVersionSettings,
                psi2IrConfiguration,
                facadeClassGenerator
        );
    }

    public static Psi2IrTranslator create(
            LanguageVersionSettings languageVersionSettings,
            Function1<? super DeserializedContainerSource, ? extends IrClass> facadeClassGenerator
    ) {
        return new Psi2IrTranslator(
                languageVersionSettings,
                new Psi2IrConfiguration(),
                facadeClassGenerator
        );
    }

}
