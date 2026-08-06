import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { finalize } from 'rxjs/operators';

export type VectorStoreType = 'ANSERINI' | 'VESPA' | 'PGVECTOR' | 'CHROMA';

interface AppConfig {
    // Core settings
    vectorStoreType: VectorStoreType;
    vectorStorePath: string;
    keywordIndexPath: string;

    // Vespa settings
    vespaEndpoint: string;
    vespaNamespace: string;
    vespaDocumentType: string;
    vespaVectorField: string;
    vespaHybridSearchEnabled: boolean;
    vespaHybridVectorWeight: number;

    // pgvector settings
    pgvectorUrl: string;
    pgvectorUsername: string;
    pgvectorPassword: string;
    pgvectorPasswordSet?: boolean;
    pgvectorTableName: string;

    // Chroma settings
    chromaHost: string;
    chromaPort: number;
    chromaCollectionName: string;
}

interface VectorStoreOption {
    value: VectorStoreType;
    label: string;
    description: string;
    icon: string;
    requiresServer: boolean;
}

interface ServiceEndpointsConfig {
    adminUrl: string;
    chatUrl: string;
    crawlUrl: string;
    stagingUrl: string;
    servingUrl: string;
    routes?: Record<string, string>;
}

@Component({
    selector: 'app-settings',
    templateUrl: './settings.component.html',
    styleUrls: ['./settings.component.css'],
    standalone: false
})
export class SettingsComponent implements OnInit {
    config: AppConfig = {
        vectorStoreType: 'ANSERINI',
        vectorStorePath: '',
        keywordIndexPath: '',
        // Vespa defaults
        vespaEndpoint: 'http://localhost:8080',
        vespaNamespace: 'default',
        vespaDocumentType: 'document',
        vespaVectorField: 'embedding',
        vespaHybridSearchEnabled: false,
        vespaHybridVectorWeight: 0.7,
        // pgvector defaults
        pgvectorUrl: 'jdbc:postgresql://localhost:5432/kompile',
        pgvectorUsername: 'postgres',
        pgvectorPassword: '',
        pgvectorTableName: 'vector_store',
        // Chroma defaults
        chromaHost: 'localhost',
        chromaPort: 8000,
        chromaCollectionName: 'kompile'
    };

    vectorStoreOptions: VectorStoreOption[] = [
        {
            value: 'ANSERINI',
            label: 'Anserini (Embedded)',
            description: 'Lucene-based embedded vector store. No external server required.',
            icon: 'storage',
            requiresServer: false
        },
        {
            value: 'VESPA',
            label: 'Vespa',
            description: 'Distributed search engine with hybrid BM25+vector search.',
            icon: 'cloud',
            requiresServer: true
        },
        {
            value: 'PGVECTOR',
            label: 'pgvector (PostgreSQL)',
            description: 'PostgreSQL with pgvector extension for vector similarity.',
            icon: 'dns',
            requiresServer: true
        },
        {
            value: 'CHROMA',
            label: 'Chroma',
            description: 'Open-source embedding database with simple API.',
            icon: 'palette',
            requiresServer: true
        }
    ];

    isLoading = false;
    saveMessage: string | null = null;
    restartRequired = false;

    serviceEndpoints: ServiceEndpointsConfig = {
        adminUrl: 'http://localhost:8080',
        chatUrl: 'http://localhost:8081',
        crawlUrl: 'http://localhost:8082',
        stagingUrl: 'http://localhost:8090',
        servingUrl: 'http://127.0.0.1:8091'
    };
    endpointsLoading = false;
    endpointsSaveMessage: string | null = null;
    endpointsSaveError = false;

    constructor(private http: HttpClient) { }

    ngOnInit() {
        this.loadConfig();
        this.loadServiceEndpoints();
    }

    loadConfig() {
        this.isLoading = true;
        this.http.get<AppConfig>('/api/config/k-app')
            .pipe(finalize(() => this.isLoading = false))
            .subscribe({
                next: (data) => {
                    // Merge with defaults to ensure all fields exist
                    this.config = { ...this.config, ...data };
                    // Don't overwrite password if it was just marked as set
                    if (data.pgvectorPasswordSet && !data.pgvectorPassword) {
                        this.config.pgvectorPassword = ''; // Keep empty, user can enter new if needed
                    }
                },
                error: (err) => {
                    console.error('Failed to load settings', err);
                }
            });
    }

    loadServiceEndpoints() {
        this.endpointsLoading = true;
        this.http.get<ServiceEndpointsConfig>('/api/service-endpoints')
            .pipe(finalize(() => this.endpointsLoading = false))
            .subscribe({
                next: (data) => this.serviceEndpoints = { ...this.serviceEndpoints, ...data },
                error: (err) => {
                    console.error('Failed to load service endpoints', err);
                    this.endpointsSaveError = true;
                    this.endpointsSaveMessage = 'Failed to load service endpoints.';
                }
            });
    }

    saveServiceEndpoints() {
        this.endpointsLoading = true;
        this.endpointsSaveMessage = null;
        this.endpointsSaveError = false;
        const updates = {
            adminUrl: this.serviceEndpoints.adminUrl,
            chatUrl: this.serviceEndpoints.chatUrl,
            crawlUrl: this.serviceEndpoints.crawlUrl,
            stagingUrl: this.serviceEndpoints.stagingUrl,
            servingUrl: this.serviceEndpoints.servingUrl
        };
        this.http.post<ServiceEndpointsConfig>('/api/service-endpoints', updates)
            .pipe(finalize(() => this.endpointsLoading = false))
            .subscribe({
                next: (data) => {
                    this.serviceEndpoints = { ...this.serviceEndpoints, ...data };
                    this.endpointsSaveMessage = 'Service endpoints saved.';
                    setTimeout(() => this.endpointsSaveMessage = null, 5000);
                },
                error: (err) => {
                    console.error('Failed to save service endpoints', err);
                    this.endpointsSaveError = true;
                    this.endpointsSaveMessage = err?.error?.error || 'Failed to save service endpoints.';
                }
            });
    }

    serviceEndpointsAreValid(): boolean {
        const values = [
            this.serviceEndpoints.adminUrl,
            this.serviceEndpoints.chatUrl,
            this.serviceEndpoints.crawlUrl,
            this.serviceEndpoints.stagingUrl,
            this.serviceEndpoints.servingUrl
        ];
        return values.every(value => {
            try {
                const parsed = new URL(value);
                return parsed.protocol === 'http:' || parsed.protocol === 'https:';
            } catch {
                return false;
            }
        });
    }

    saveSettings() {
        this.isLoading = true;
        this.saveMessage = null;
        this.restartRequired = false;

        // Prepare config for saving - don't send empty password
        const configToSave = { ...this.config };
        if (!configToSave.pgvectorPassword) {
            delete (configToSave as any).pgvectorPassword;
        }

        this.http.put<{
            vectorStoreType: string,
            vectorStorePath: string,
            keywordIndexPath: string,
            switched: boolean,
            restartRequired: boolean,
            message: string
        }>('/api/config/k-app', configToSave)
            .pipe(finalize(() => this.isLoading = false))
            .subscribe({
                next: (response) => {
                    this.config.vectorStorePath = response.vectorStorePath;
                    this.config.keywordIndexPath = response.keywordIndexPath;
                    this.config.vectorStoreType = response.vectorStoreType as VectorStoreType;

                    this.saveMessage = response.message;
                    this.restartRequired = response.restartRequired;

                    // Clear message after 5 seconds
                    setTimeout(() => {
                        this.saveMessage = null;
                    }, 5000);
                },
                error: (err) => {
                    console.error('Failed to save settings', err);
                    this.saveMessage = 'Failed to save settings. Please check server logs.';
                    this.restartRequired = false;
                }
            });
    }

    getSelectedStoreOption(): VectorStoreOption | undefined {
        return this.vectorStoreOptions.find(o => o.value === this.config.vectorStoreType);
    }
}
