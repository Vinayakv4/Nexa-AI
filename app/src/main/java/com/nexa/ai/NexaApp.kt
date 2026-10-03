package com.nexa.ai

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.nexa.ai.agent.AgentScheduler

class NexaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        AgentScheduler.ensureChannel(this)
        AgentScheduler.ensurePeriodicTick(this)
    }
}
