package echo.music.iad1tya.di

import android.content.Context
import com.music.echo.proxy.ProxyRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import echo.music.iad1tya.utils.NetworkConnectivityObserver
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

  @Provides
  @Singleton
  fun provideNetworkConnectivityObserver(
    @ApplicationContext context: Context
  ): NetworkConnectivityObserver {
    return NetworkConnectivityObserver(context)
  }

  @Provides
  @Singleton
  fun provideProxyRouter(
    @ApplicationContext context: Context,
    @ApplicationScope applicationScope: CoroutineScope
  ): ProxyRouter {
    ProxyRouter.initialize(context, applicationScope)
    return ProxyRouter
  }
}
