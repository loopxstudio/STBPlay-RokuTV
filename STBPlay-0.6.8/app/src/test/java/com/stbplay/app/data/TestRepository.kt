package com.stbplay.app.data

import com.stbplay.app.data.db.AppDatabase
import org.mockito.MockMakers
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings

/** Network-only tests do not open an Android database. */
internal fun testRepository(): IptvRepository = IptvRepository(
    mock(AppDatabase::class.java, withSettings().mockMaker(MockMakers.SUBCLASS))
)
