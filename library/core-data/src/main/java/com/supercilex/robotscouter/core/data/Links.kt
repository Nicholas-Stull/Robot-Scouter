package com.supercilex.robotscouter.core.data

import android.net.Uri
import androidx.annotation.WorkerThread
import com.google.firebase.appindexing.Action
import com.google.firebase.appindexing.Indexable
import com.google.firebase.appindexing.Scope
import com.google.firebase.appindexing.builders.Actions
import com.google.firebase.appindexing.builders.Indexables
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.supercilex.robotscouter.common.FIRESTORE_ACTIVE_TOKENS
import com.supercilex.robotscouter.common.FIRESTORE_NUMBER
import com.supercilex.robotscouter.common.FIRESTORE_PREV_UID
import com.supercilex.robotscouter.common.FIRESTORE_REF
import com.supercilex.robotscouter.common.FIRESTORE_TIMESTAMP
import com.supercilex.robotscouter.common.FIRESTORE_TOKEN
import com.supercilex.robotscouter.core.await
import com.supercilex.robotscouter.core.data.model.userDeletionQueue
import com.supercilex.robotscouter.core.logCrashLog
import com.supercilex.robotscouter.core.logFailures
import com.supercilex.robotscouter.core.model.Team
import java.io.File
import java.util.Date

const val APP_LINK_BASE = "https://supercilex.github.io/Robot-Scouter/data/"
const val ACTION_FROM_DEEP_LINK = "com.supercilex.robotscouter.action.FROM_DEEP_LINK"
const val KEYS = "keys"

private val TEAMS_LINK_BASE = "$APP_LINK_BASE${teamsRef.id}/"
private val TEMPLATES_LINK_BASE = "$APP_LINK_BASE${templatesRef.id}/"

val Team.deepLink: String get() = listOf(this).getTeamsLink()

val Team.viewAction: Action get() = Actions.newView(toString(), deepLink)

@get:WorkerThread
val Team.indexable: Indexable
    get() = Indexables.digitalDocumentBuilder()
            .setUrl(deepLink)
            .setName(toString())
            .apply {
                val media = media
                if (media?.isNotBlank() == true && !File(media).exists()) {
                    setImage(media)
                }
            }
            .setMetadata(Indexable.Metadata.Builder()
                                 .setWorksOffline(true)
                                 .setScope(Scope.CROSS_DEVICE))
            .build()

fun List<Team>.getTeamsLink(token: String? = null): String =
        generateUrl(TEAMS_LINK_BASE, token) { id to number.toString() }

fun getTemplateLink(templateId: String, token: String? = null): String =
        listOf(templateId).generateUrl(TEMPLATES_LINK_BASE, token) { this to true.toString() }

fun getTemplateViewAction(id: String, name: String): Action =
        Actions.newView(name, getTemplateLink(id))

fun getTemplateIndexable(templateId: String, templateName: String): Indexable =
        Indexables.digitalDocumentBuilder()
                .setUrl(getTemplateLink(templateId))
                .setName(templateName)
                .setMetadata(Indexable.Metadata.Builder()
                                     .setWorksOffline(true)
                                     .setScope(Scope.CROSS_DEVICE))
                .build()

suspend fun List<DocumentReference>.share(
        block: Boolean,
        deletionGenerator: (token: String, ids: List<String>) -> QueuedDeletion.ShareToken
): String {
    val token = generateToken()
    val tokenPath = FieldPath.of(FIRESTORE_ACTIVE_TOKENS, token)
    val timestamp = Date()

    val update = firestoreBatch {
        forEach { update(it, tokenPath, timestamp) }
        set(userDeletionQueue, deletionGenerator(token, map { it.id }).data, SetOptions.merge())
    }.logFailures(this, token)

    if (block) update.await()

    return token
}

suspend fun updateOwner(
        refs: Iterable<DocumentReference>,
        token: String,
        prevUid: String?,
        newValue: (DocumentReference) -> Any
) = refs.map { ref ->
    FirebaseFunctions.getInstance()
            .getHttpsCallable("updateOwners")
            .call(mapOf(
                    FIRESTORE_TOKEN to token,
                    FIRESTORE_REF to ref.path,
                    FIRESTORE_PREV_UID to prevUid,
                    run {
                        val value = newValue(ref)
                        when (value) {
                            is Number -> FIRESTORE_NUMBER to value
                            is Date -> FIRESTORE_TIMESTAMP to value.time
                            else -> error("Unknown data type (${value.javaClass}): $value")
                        }
                    }
            ))
            .addOnFailureListener {
                if (it is FirebaseFunctionsException) {
                    logCrashLog("Functions failed (${it.code}) with details: ${it.details}")
                }
            }
            .logFailures(ref, "Token: $token, from user: $prevUid")
}.forEach {
    it.await()
}

private inline fun <T> List<T>.generateUrl(
        linkBase: String,
        token: String?,
        crossinline queryParamsGenerator: T.() -> Pair<String, String>
): String {
    val builder = Uri.Builder().path(linkBase).encodeToken(token)

    builder.appendQueryParameter(KEYS, joinToString(",") {
        val (key, value) = it.queryParamsGenerator()
        builder.appendQueryParameter(key, value)
        key
    })

    return Uri.decode(builder.build().toString())
}

private fun Uri.Builder.encodeToken(token: String?): Uri.Builder =
        token?.let { appendQueryParameter(FIRESTORE_ACTIVE_TOKENS, it) } ?: this

private fun generateToken() = FirebaseFirestore.getInstance().collection("null").document().id
