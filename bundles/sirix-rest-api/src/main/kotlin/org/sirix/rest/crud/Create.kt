package org.sirix.rest.crud

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Context
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.ext.auth.oauth2.OAuth2Auth
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.executeBlockingAwait
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.auth.isAuthorizedAwait
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.sirix.access.Databases
import org.sirix.access.conf.DatabaseConfiguration
import org.sirix.access.conf.ResourceConfiguration
import org.sirix.api.Database
import org.sirix.api.ResourceManager
import org.sirix.api.XdmNodeWriteTrx
import org.sirix.rest.Auth
import org.sirix.rest.Serialize
import org.sirix.service.xml.serialize.XMLSerializer
import org.sirix.service.xml.shredder.XMLShredder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

// For instance: curl -k -X POST -d "<xml/>" -u admin https://localhost:8443/database/resource1
class Create(private val location: Path, private val keycloak: OAuth2Auth) {
    suspend fun handle(ctx: RoutingContext) {
        val databaseName = ctx.pathParam("database")

        val user = Auth(keycloak).authenticateUser(ctx)

        val isAuthorized = user.isAuthorizedAwait("realm:create")

        if (!isAuthorized) {
            ctx.fail(HttpResponseStatus.UNAUTHORIZED.code())
            return
        }

        val resource = ctx.pathParam("resource")
        val resToStore = ctx.bodyAsString

        if (databaseName == null || resToStore == null || resToStore.isBlank()) {
            ctx.fail(IllegalArgumentException("Database name and resource data to store not given."))
            return
        }

        shredder(databaseName, resource, resToStore, ctx)
    }

    private suspend fun shredder(dbPathName: String, resPathName: String = dbPathName, resFileToStore: String,
                                 ctx: RoutingContext) {
        val dbFile = location.resolve(dbPathName)
        val context = ctx.vertx().orCreateContext
        val dispatcher = ctx.vertx().dispatcher()
        val dbConfig = createDatabaseIfNotExists(dbFile, dispatcher)
        val database = Databases.openDatabase(dbFile)

        database.use {
            val resConfig = ResourceConfiguration.Builder(resPathName, dbConfig).build()

            createOrRemoveAndCreateResource(database, resConfig, resPathName, dispatcher)

            val manager = database.getResourceManager(resPathName)

            manager.use {
                val wtx = manager.beginNodeWriteTrx()
                insertSubtreeAsFirstChild(wtx, resFileToStore, context)
                serialize(manager, context, ctx)
            }
        }
    }

    private suspend fun serialize(manager: ResourceManager?, vertxContext: Context, routingCtx: RoutingContext) {
        vertxContext.executeBlockingAwait(Handler<Future<Nothing>> {
            val out = ByteArrayOutputStream()
            val serializerBuilder = XMLSerializer.XMLSerializerBuilder(manager, out)
            val serializer = serializerBuilder.emitIDs().emitRESTful().emitRESTSequence().prettyPrint().build()

            Serialize().serializeXml(serializer, out, routingCtx)

            it.complete(null)
        })
    }

    private suspend fun createDatabaseIfNotExists(dbFile: Path,
                                                  dispatcher: CoroutineDispatcher): DatabaseConfiguration {
        return withContext(dispatcher) {
            val dbExists = Files.exists(dbFile)

            if (!dbExists)
                Files.createDirectories(dbFile.parent)

            val dbConfig = DatabaseConfiguration(dbFile)

            if (!Databases.existsDatabase(dbFile)) {
                Databases.createDatabase(dbConfig)
            }

            dbConfig
        }
    }

    private suspend fun createOrRemoveAndCreateResource(database: Database, resConfig: ResourceConfiguration?,
                                                        resPathName: String, dispatcher: CoroutineDispatcher) {
        withContext(dispatcher) {
            if (!database.createResource(resConfig)) {
                database.removeResource(resPathName)
                database.createResource(resConfig)
            }
        }
    }

    private suspend fun insertSubtreeAsFirstChild(wtx: XdmNodeWriteTrx, resFileToStore: String, context: Context) {
        context.executeBlockingAwait(Handler<Future<Nothing>> {
            wtx.use {
                wtx.insertSubtreeAsFirstChild(XMLShredder.createStringReader(resFileToStore))
            }

            it.complete(null)
        })
    }
}
