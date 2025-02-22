package com.wrbug.developerhelper.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wrbug.developerhelper.R
import com.wrbug.developerhelper.basecommon.BaseApp
import com.wrbug.developerhelper.basecommon.showToast
import com.wrbug.developerhelper.commonutil.AppInfoManager
import com.wrbug.developerhelper.commonutil.entity.ApkInfo
import com.wrbug.developerhelper.commonutil.entity.TopActivityInfo
import com.wrbug.developerhelper.constant.ReceiverConstant
import com.wrbug.developerhelper.basecommon.entry.HierarchyNode
import com.wrbug.developerhelper.commonutil.shell.Callback
import com.wrbug.developerhelper.commonutil.shell.ShellManager
import com.wrbug.developerhelper.ui.activity.hierachy.HierarchyActivity
import com.wrbug.developerhelper.commonutil.UiUtils


class DeveloperHelperAccessibilityService : AccessibilityService() {
    private val receiver = DeveloperHelperAccessibilityReceiver()
    private var nodeId = 0L
    private var currentAppInfo: ApkInfo? = null

    companion object {
        internal var serviceRunning = false
        fun isAccessibilitySettingsOn(): Boolean {
            var accessibilityEnabled = 0
            val service = "com.wrbug.developerhelper/" + DeveloperHelperAccessibilityService::class.java.canonicalName
            try {
                accessibilityEnabled = Settings.Secure.getInt(
                    BaseApp.instance.applicationContext.contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (e: Settings.SettingNotFoundException) {
            }

            val mStringColonSplitter = TextUtils.SimpleStringSplitter(':')

            if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(
                    BaseApp.instance.applicationContext.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (settingValue != null) {
                    mStringColonSplitter.setString(settingValue)
                    while (mStringColonSplitter.hasNext()) {
                        val accessibilityService = mStringColonSplitter.next()
                        if (accessibilityService.equals(service, ignoreCase = true)) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        //todo java.lang.RuntimeException:android.os.TransactionTooLargeException
        val nodeMap: HashMap<Long, HierarchyNode> = hashMapOf()
    }

    override fun onInterrupt() {
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    fun readNode(topActivityInfo: TopActivityInfo?): ArrayList<HierarchyNode> {
        val hierarchyNodes = arrayListOf<HierarchyNode>()
        nodeMap.clear()
        if (rootInActiveWindow != null) {
            rootInActiveWindow.packageName?.run {
                currentAppInfo = AppInfoManager.getAppByPackageName(toString())
            }
            val node = getDecorViewNode(rootInActiveWindow)
            readNodeInfo(
                hierarchyNodes,
                node ?: rootInActiveWindow,
                null,
                topActivityInfo
            )
        }
        return hierarchyNodes
    }

    private fun getDecorViewNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            if (child.viewIdResourceName == "android:id/content") {
                return child
            }
            val decorViewNode = getDecorViewNode(child)
            if (decorViewNode != null) {
                return decorViewNode
            }
        }
        return null
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter()
        filter.addAction(ReceiverConstant.ACTION_HIERARCHY_VIEW)
        registerReceiver(receiver, filter)
        sendStatusBroadcast(true)
        serviceRunning = true
        nodeMap.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        nodeMap.clear()
        serviceRunning = false
        unregisterReceiver(receiver)
        sendStatusBroadcast(false)
    }

    private fun setNodeId(node: HierarchyNode) {
        node.id = nodeId
        nodeId++
    }

    private fun sendStatusBroadcast(running: Boolean) {
        val intent = Intent(ReceiverConstant.ACTION_ACCESSIBILITY_SERVICE_STATUS_CHANGED)
        intent.putExtra("status", running)
        sendBroadcast(intent)
    }

    private fun readNodeInfo(
        hierarchyNodes: ArrayList<HierarchyNode>,
        accessibilityNodeInfo: AccessibilityNodeInfo,
        parentNode: HierarchyNode?,
        topActivityInfo: TopActivityInfo?
    ) {
        if (accessibilityNodeInfo.childCount == 0) {
            return
        }
        for (index in 0 until accessibilityNodeInfo.childCount) {
            val child = accessibilityNodeInfo.getChild(index)
            if (child.isVisibleToUser.not()) {
                continue
            }
            val screenRect = Rect()
            val parentRect = Rect()
            child.getBoundsInScreen(screenRect)
            child.getBoundsInParent(parentRect)
            screenRect.offset(0, -UiUtils.getStatusHeight())
            val node = HierarchyNode()
            setNodeId(node)
            if (parentNode != null) {
                parentNode.childId.add(node)
                nodeMap[node.id] = node
                node.parentId = parentNode.id
            } else {
                hierarchyNodes.add(node)
                nodeMap[node.id] = node
            }
            child.viewIdResourceName?.let {
                node.resourceId = it
                if (it.contains(":id")) {
                    node.idHex = topActivityInfo?.viewIdHex?.get(it.substring(it.indexOf("id")))
                }
            }
            var text = ""
            if (child.text != null) {
                text = child.text.toString()
            }
            node.text = text
            node.screenBounds = screenRect
            node.parentBounds = parentRect
            node.checkable = child.isCheckable
            node.checked = child.isChecked
            node.widget = if (child.className == null) {
                ""
            } else {
                child.className.toString()
            }
            node.clickable = child.isClickable
            node.contentDesc = if (child.contentDescription == null) {
                ""
            } else {
                child.contentDescription.toString()
            }
            node.enabled = child.isEnabled
            node.focusable = child.isFocusable
            node.focused = child.isFocused
            node.longClickable = child.isLongClickable
            node.packageName = if (child.packageName == null) {
                ""
            } else {
                child.packageName.toString()
            }
            node.password = child.isPassword
            node.scrollable = child.isScrollable
            node.selected = child.isSelected
            readNodeInfo(
                hierarchyNodes,
                child,
                node,
                topActivityInfo
            )
        }
    }


    inner class DeveloperHelperAccessibilityReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, data: Intent?) {
            showToast(getString(R.string.getting_app_info))
            ShellManager.getTopActivity(object : Callback<TopActivityInfo?> {

                override fun onSuccess(data: TopActivityInfo?) {
                    val nodesInfo = readNode(data)
                    HierarchyActivity.start(context, currentAppInfo, nodesInfo, data)
                }
            })

        }
    }

}