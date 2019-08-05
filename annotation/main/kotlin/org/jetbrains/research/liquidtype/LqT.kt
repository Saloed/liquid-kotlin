package org.jetbrains.research.liquidtype

import org.intellij.lang.annotations.Language

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
//public annotation class LqT(@Language("kotlin", prefix = "fun condition() = ") val condition: String = "true")
public annotation class LqT(@Language("kotlin", prefix = "fun condition(): Boolean = ") val condition: String)

public inline fun <reified T> evaluateCondition(argument: T, condition: (T) -> Boolean): Boolean = condition(argument)

