// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.liveTemplate

import com.intellij.codeInsight.template.impl.DefaultLiveTemplatesProvider

/**
 * @author Irina.Chernushina on 10/26/2017.
 */
class VueTemplatesProvider : DefaultLiveTemplatesProvider {
  override fun getDefaultLiveTemplateFiles(): Array<String> = arrayOf("liveTemplates/Vue")

  override fun getHiddenLiveTemplateFiles(): Array<String>? = null
}
