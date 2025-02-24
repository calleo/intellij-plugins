// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.codeInsight

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.completion.JSCompletionUtil
import com.intellij.lang.javascript.completion.JSLookupPriority
import com.intellij.lang.javascript.completion.JSLookupUtilImpl
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import java.util.*

/**
 * @author Irina.Chernushina on 10/13/2017.
 */
class VueComponentDetailsProvider {
  companion object {
    val INSTANCE: VueComponentDetailsProvider = VueComponentDetailsProvider()
    private val ADVANCED_PROVIDERS = listOf(VueMixinLocalComponentDetailsProvider(), VueGlobalMixinComponentDetailsProvider(),
                                            VueExtendsLocalComponentDetailsProvider())
    private val BIND_VARIANTS = setOf(".prop", ".camel", ".sync")
    private val ON_VARIANTS = setOf("*")
    private val PREFIX_VARIANTS = mapOf(Pair(":", BIND_VARIANTS),
                                        Pair("v-bind:", BIND_VARIANTS),
                                        Pair("@", ON_VARIANTS), Pair("v-on:", ON_VARIANTS))
    private val EVENT_MODIFIERS = setOf(".stop", ".prevent", ".capture", ".self", ".once", ".passive", ".native")
    private val NO_VALUE = mapOf(Pair("@", EVENT_MODIFIERS), Pair("v-on:", EVENT_MODIFIERS))

    fun attributeAllowsNoValue(attributeName: String): Boolean {
      return NO_VALUE.any {
        val cutPrefix = attributeName.substringAfter(it.key, "")
        !cutPrefix.isEmpty() && it.value.any { cutPrefix.endsWith(it) }
      }
    }

    fun getBoundName(attributeName: String): String? {
      return PREFIX_VARIANTS.mapNotNull {
        val after = attributeName.substringAfter(it.key, "")
        if (after.isNotEmpty()) {
          return after.substringBefore(".", after)
        }
        return@mapNotNull null
      }.firstOrNull()
             ?: if (attributeName.contains('.')) {
               // without prefix, but might be with postfix
               attributeName.substringBefore(".", "")
             }
             // if just attribute name should be used, return null
             else null
    }

    fun nameVariantsFilter(attributeName: String): (String, PsiElement) -> Boolean {
      val prefix = PREFIX_VARIANTS.keys.find { attributeName.startsWith(it) }
      val normalizedName = if (prefix != null) attributeName.substring(prefix.length) else attributeName
      val nameVariants = getNameVariants(normalizedName, true)
      return { name, _ -> name in nameVariants }
    }
  }

  fun getAttributes(descriptor: JSObjectLiteralExpression?,
                    project: Project,
                    onlyPublic: Boolean,
                    xmlContext: Boolean): List<VueAttributeDescriptor> {
    val result: MutableList<VueAttributeDescriptor> = mutableListOf()
    if (descriptor != null) {
      result.addAll(VueComponentOwnDetailsProvider.getDetails(descriptor, EMPTY_FILTER, onlyPublic, false))
      result.addAll(VueDirectivesProvider.getAttributes(descriptor, descriptor.project))
    }
    iterateProviders(descriptor, project, {
      result.addAll(VueComponentOwnDetailsProvider.getDetails(it, EMPTY_FILTER, onlyPublic, false))
      true
    })

    return result.map {
      when {
        xmlContext -> it.createNameVariant(fromAsset(it.name))
        it.name.contains('-') -> it.createNameVariant(toAsset(it.name))
        else -> it
      }
    }
  }

  fun getAttributesAndCreateLookupElements(location: PsiElement, priority: JSLookupPriority): ArrayList<LookupElement>? {
    val scriptWithExport = findScriptWithExport(location.originalElement) ?: return null
    val defaultExport = scriptWithExport.second
    val obj = defaultExport.stubSafeElement as? JSObjectLiteralExpression
    val vueVariants = ArrayList<LookupElement>()
    VueComponentDetailsProvider.INSTANCE.getAttributes(obj, location.project, false, false)
      // do not suggest directives in injected javascript fragments
      .filter { !it.isDirective() }
      .forEach {
        val builder = if (it.declaration == null) LookupElementBuilder.create(it.name)
        else JSLookupUtilImpl.createLookupElement(it.declaration!!, it.name)
        vueVariants.add(JSCompletionUtil.withJSLookupPriority(builder, priority))
      }
    return vueVariants
  }

  fun resolveAttribute(descriptor: JSObjectLiteralExpression,
                       attrName: String,
                       onlyPublic: Boolean): VueAttributeDescriptor? {
    val filter = nameVariantsFilter(attrName)
    val direct = VueComponentOwnDetailsProvider.getDetails(descriptor, filter, onlyPublic, true).firstOrNull()
    if (direct != null) return direct
    val holder: Ref<VueAttributeDescriptor> = Ref()
    iterateProviders(descriptor, descriptor.project, {
      holder.set(VueComponentOwnDetailsProvider.getDetails(it, filter, onlyPublic, true).firstOrNull())
      holder.isNull
    })
    if (holder.isNull) {
      holder.set(VueDirectivesProvider.resolveAttribute(descriptor, attrName, descriptor.project))
    }
    return holder.get()
  }

  fun processLocalComponents(component: JSObjectLiteralExpression?,
                             project: Project,
                             processor: (String?, PsiElement) -> Boolean) {
    val filter: (String, PsiElement) -> Boolean = { name, element -> !processor(name, element) }
    if (component != null) {
      // no need to accumulate the components, it is done in the processor
      // but we should check if processor already commanded to stop
      val direct = VueComponentOwnDetailsProvider.getLocalComponents(component, filter, true)
      if (direct.isNotEmpty()) return
    }
    iterateProviders(component, project, { mixedInDescriptor ->
      VueComponentOwnDetailsProvider.getLocalComponents(mixedInDescriptor, filter, true).isEmpty()
    })
  }

  private fun iterateProviders(descriptor: JSObjectLiteralExpression?,
                               project: Project,
                               processor: (JSObjectLiteralExpression) -> Boolean) {
    val visited = mutableSetOf<JSObjectLiteralExpression>()
    val queue = ArrayDeque<Ref<JSObjectLiteralExpression>>()
    queue.add(Ref(descriptor))
    if (descriptor != null) visited.add(descriptor)

    while (queue.isNotEmpty()) {
      val currentDescriptor = queue.removeFirst()?.get()
      for (provider in ADVANCED_PROVIDERS) {
        val finder = provider.getDescriptorFinder()
        val shouldStop = provider.getIndexedData(currentDescriptor, project).any { implicitElement ->
          val obj = finder(implicitElement) ?: return@any false
          if (visited.add(obj)) queue.add(Ref(obj))

          !processor(obj)
        }
        if (shouldStop) return
      }
    }
  }
}
