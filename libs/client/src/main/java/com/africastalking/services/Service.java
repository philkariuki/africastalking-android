package com.africastalking.services;

import android.content.Context;

import com.africastalking.AfricasTalking;
import com.africastalking.Callback;
import com.africastalking.Environment;
import com.africastalking.Logger;
import com.africastalking.proto.SdkServerServiceGrpc;
import com.google.gson.GsonBuilder;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.africastalking.proto.SdkServerServiceGrpc.*;
import com.africastalking.proto.SdkServerServiceOuterClass.*;

import org.pjsip.pjsua2.Account;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.stub.MetadataUtils;
import okhttp3.*;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.*;
import retrofit2.Call;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;

import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;


/**
 * A given service offered by AT API
 */
public abstract class Service {

    static final String PRODUCTION_DOMAIN = "africastalking.com";
    static final String SANDBOX_DOMAIN = "sandbox.africastalking.com";


    private static final Metadata.Key<String> CLIENT_ID_HEADER_KEY = Metadata.Key.of("X-Client-Id", ASCII_STRING_MARSHALLER);


    public static String USERNAME;
    public static String HOST;
    public static int PORT = 35897;

    public static Environment ENV = Environment.PRODUCTION;
    public static Boolean LOGGING = false;
    public static Logger LOGGER = new Logger() {
        @Override
        public void log(String message, Object... args) {
            System.out.println(String.format(message, args));
        }
    };


    Retrofit.Builder retrofitBuilder;
    private ClientTokenResponse token;

    Service() throws IOException {

        OkHttpClient.Builder httpClient = new OkHttpClient.Builder();


        if (LOGGING) {
            HttpLoggingInterceptor logger = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
                @Override
                public void log(String message) {
                    LOGGER.log(message);
                }
            });
            logger.setLevel(HttpLoggingInterceptor.Level.BASIC);
            httpClient.addInterceptor(logger);
        }

        httpClient.addInterceptor(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {

                if (token == null || token.getExpiration() < System.currentTimeMillis()) {
                    fetchToken(HOST, PORT);
                    if (token == null) {
                        throw new IOException("Failed to fetch token");
                    }
                }

                Request original = chain.request();
                Request request = original.newBuilder()
                        .addHeader("ApiKey", token.getToken()) // FIXME: Token
                        .addHeader("Accept", "application/json")
                        .build();

                return chain.proceed(request);
            }
        });


        retrofitBuilder = new Retrofit.Builder()
                .addConverterFactory(GsonConverterFactory.create(new GsonBuilder().setLenient().create())) // switched from ScalarsConverterFactory
                .client(httpClient.build());

        initService();
    }

    SdkServerServiceBlockingStub addClientIdentification(SdkServerServiceBlockingStub stub) {
        // Optional auth header
        String clientId = AfricasTalking.getClientId();
        if (clientId != null) {
            Metadata headers = new Metadata();
            headers.put(CLIENT_ID_HEADER_KEY, clientId);
            stub = MetadataUtils.attachHeaders(stub, headers);
        }
        return stub;
    }

    public static ManagedChannel getChannel(String host, int port) {
        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext(true); // FIXME: Remove to Setup TLS
        return channelBuilder.build();
    }

    void fetchServiceToken(String host, int port, ClientTokenRequest.Capability capability) throws IOException {

        if (LOGGING) { LOGGER.log("Fetching token..."); }

        ManagedChannel channel = getChannel(host, port);
        SdkServerServiceBlockingStub stub = addClientIdentification(SdkServerServiceGrpc.newBlockingStub(channel));
        ClientTokenRequest req = ClientTokenRequest.newBuilder()
                .setCapability(capability)
                .setEnvironment(ENV.toString())
                .build();
        token = stub.getToken(req);

        if (LOGGING) {
            LOGGER.log(
                "\n\nToken: %s\nExpires: %s\n",
                token.getToken(),
                String.valueOf(token.getExpiration()));
        }
    }


    public static <T extends Service> T newInstance(String service) throws IOException {

        if (service.contentEquals("account")) {
            if (AccountService.sInstance == null) {
                AccountService.sInstance = new AccountService();
            }
            return (T) AccountService.sInstance;
        }


        if (service.contentEquals("airtime")) {
            if (AirtimeService.sInstance == null) {
                AirtimeService.sInstance = new AirtimeService();
            }
            return (T) AirtimeService.sInstance;
        }


        if (service.contentEquals("payment")) {
            if (PaymentService.sInstance == null) {
                PaymentService.sInstance = new PaymentService();
            }
            return (T) PaymentService.sInstance;
        }


        if (service.contentEquals("sms")) {
            if (SmsService.sInstance == null) {
                SmsService.sInstance = new SmsService();
            }
            return (T) SmsService.sInstance;
        }

        if (service.contentEquals("voice")) {
            if (VoiceService.sInstance == null) {
                VoiceService.sInstance = new VoiceService();
            }
            return (T) VoiceService.sInstance;
        }

        throw new IOException("Invalid service");
    }


    /**
     *
     * @param cb
     * @param <T>
     * @return
     */
    protected <T> retrofit2.Callback<T> makeCallback(final Callback<T> cb) {
        return new retrofit2.Callback<T>() {
            @Override
            public void onResponse(Call<T> call, retrofit2.Response<T> response) {
                if (response.isSuccessful()) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onFailure(new Exception(response.message()));
                }
            }

            @Override
            public void onFailure(Call<T> call, Throwable t) {
                cb.onFailure(t);
            }
        };
    }


    protected abstract void fetchToken(String host, int port) throws IOException;

    /**
     * Get an instance of a service.
     * @param <T>
     * @return
     */
    protected abstract <T extends Service> T getInstance() throws IOException;

    /**
     * Check if a service is initialized
     * @return boolean true if yes, false otherwise
     */
    protected abstract boolean isInitialized();

    /**
     * Initializes a service
     */
    protected abstract void initService();

    /**
     * Destroys a service
     */
    protected abstract void destroyService();
}
