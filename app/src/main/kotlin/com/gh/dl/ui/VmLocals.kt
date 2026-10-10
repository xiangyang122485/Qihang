package com.gh.dl.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController

/**
 * 各页的 ViewModel 通过 CompositionLocal 暴露给整棵 Compose 树，
 * 这样各 Tab / 设置子页 / 弹窗无需层层传参即可取用自己归属的那份状态，
 * 同时也避免为了解耦而把状态重新塞回全局 object。
 *
 * 生命周期：4 个 VM 在根 [GhApp] 用 viewModel() 创建（owner = MainActivity），
 * 因此与 Activity 同生命周期，配置变更（旋转等）不会重建、状态不丢。
 */
val LocalParserVM = compositionLocalOf<ParserViewModel> { error("ParserViewModel 未通过 CompositionLocalProvider 提供") }
val LocalDownloadsVM = compositionLocalOf<DownloadsViewModel> { error("DownloadsViewModel 未通过 CompositionLocalProvider 提供") }
val LocalSearchVM = compositionLocalOf<SearchViewModel> { error("SearchViewModel 未通过 CompositionLocalProvider 提供") }
val LocalSettingsVM = compositionLocalOf<SettingsViewModel> { error("SettingsViewModel 未通过 CompositionLocalProvider 提供") }
val LocalBrowseVM = compositionLocalOf<BrowseViewModel> { error("BrowseViewModel 未通过 CompositionLocalProvider 提供") }

/**
 * 设置页的 [NavHostController]。在根 [GhApp] 用 rememberNavController() 创建（owner 与 Activity 同生命周期，
 * 切 Tab / 旋转不重建），通过 provider 暴露给设置根页与所有设置子页，使它们无需层层传参即可导航。
 *
 * 之所以独立成 CompositionLocal 而非附加在某 ViewModel 上：设置子页是纯 Composable、不在 ViewModel 里，
 * 且跨 Tab 跳转（解析页「线路诊断」、克隆卡「去配置 SSH」）也需要在设置 NavHost 未挂载时记下目标、待挂载后消费。
 */
val LocalSettingsNav = compositionLocalOf<NavHostController> { error("LocalSettingsNav 未通过 CompositionLocalProvider 提供") }

// 仅用于消除「ViewModel 子类未被显式引用」的未使用警告（viewModel() 工厂依赖它）
@Suppress("unused")
val vmClasses: Array<Class<out ViewModel>> = arrayOf(
    ParserViewModel::class.java,
    DownloadsViewModel::class.java,
    SearchViewModel::class.java,
    SettingsViewModel::class.java,
    BrowseViewModel::class.java,
)
