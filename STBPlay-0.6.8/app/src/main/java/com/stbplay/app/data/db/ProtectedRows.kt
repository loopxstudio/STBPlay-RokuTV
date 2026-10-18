package com.stbplay.app.data.db

import com.stbplay.app.data.SecureValues

internal fun PlaylistEntity.protect() = copy(url = SecureValues.encrypt(url), usernameOrMac = SecureValues.encrypt(usernameOrMac),
    password = SecureValues.encrypt(password), epgUrl = SecureValues.encrypt(epgUrl))
internal fun PlaylistEntity.reveal() = copy(url = SecureValues.decrypt(url), usernameOrMac = SecureValues.decrypt(usernameOrMac),
    password = SecureValues.decrypt(password), epgUrl = SecureValues.decrypt(epgUrl))
internal fun MediaEntity.protect() = copy(streamUrl = SecureValues.encrypt(streamUrl), cmd = cmd?.let(SecureValues::encrypt), logo = logo?.let(SecureValues::encrypt))
internal fun MediaEntity.reveal() = copy(streamUrl = SecureValues.decrypt(streamUrl), cmd = cmd?.let(SecureValues::decrypt), logo = logo?.let(SecureValues::decrypt))
internal fun FavoriteEntity.protect() = copy(payload = SecureValues.encrypt(payload))
internal fun FavoriteEntity.reveal() = copy(payload = SecureValues.decrypt(payload))
