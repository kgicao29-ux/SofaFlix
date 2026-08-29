package com.sofaflix.cloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SofaFlixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SofaFlixProvider())
    }
}
