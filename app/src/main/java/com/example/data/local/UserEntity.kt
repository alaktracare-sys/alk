package com.example.data.local

import com.example.model.User

data class UserEntity(
    val id: Int,
    val username: String,
    val email: String,
    val lastSyncedAt: Long = System.currentTimeMillis()
) {
    fun toUser(): User {
        return User(
            id = id,
            username = username,
            email = email
        )
    }

    companion object {
        fun fromUser(user: User, lastSyncedAt: Long = System.currentTimeMillis()): UserEntity {
            return UserEntity(
                id = user.id,
                username = user.username,
                email = user.email,
                lastSyncedAt = lastSyncedAt
            )
        }
    }
}
