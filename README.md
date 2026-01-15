# PDF Layout-Preserving Translator

A high-fidelity PDF translation tool that leverages Large Language Models (LLMs) to translate document content while strictly preserving the original layout, styles, and reading order.

## Overview

This project provides an end-to-end pipeline for translating complex PDF documents. Unlike traditional PDF-to-text translators, it performs deep layout analysis to ensure that the translated output mirrors the visual structure of the original file, including multi-column layouts, tables, and centered/aligned text.

### Key Features

- **Layout Fidelity**: Intelligently maintains complex visual structures such as sidebars, headers, footers, and multi-column text blocks.
- **Style Preservation**: Automatically detects and replicates font sizes, bold/italic formatting, and text alignments (Left, Center, Right).
- **Tabular Data Translation**: Processes tables while keeping row/column structures intact and maintaining cell alignments.
- **Context-Aware LLM Translation**: Uses document-level context (titles, subject matter) to improve terminology accuracy and consistency across different pages.
- **Advanced Reading Order Detection**: Ensures text is processed in the correct logical sequence, preventing "scrambled" output in multi-column or complex documents.
- **Smart URL & List Handling**: Automatically fixes URLs split across lines and preserves hierarchical list structures (bullets, numbered lists).
- **Performance Optimized**: Includes a translation caching layer and efficient layout analysis strategies.

## Architecture

The system is built with a modular approach:

- **PdfTranslator**: The core engine that orchestrates parsing, layout analysis, translation, and rendering.
- **PdfLayoutAnalyzer**: Analyzes page geometry to identify logical blocks using specialized strategies (`SingleColumn`, `MultiColumn`, `TableDominant`).
- **SiliconFlowClient**: Integrates with the SiliconFlow API (supporting models like DeepSeek-V3) for high-quality LLM translation.
- **PdfRenderer**: Reconstructs the translated document into a new PDF with appropriate font mapping.

## Getting Started

### Prerequisites

1.  **API Key**: Obtain a SiliconFlow API key from [SiliconFlow](https://siliconflow.cn/).
2.  **Java Environment**: JDK 8 or higher.
3.  **Fonts**: Place the required TTF fonts in `src/main/resources/fonts` to ensure correct rendering of translated text.

### Configuration

Set your API credentials in `src/main/resources/config.properties`:

```properties
api.key=your_siliconflow_api_key
model.name=deepseek-ai/DeepSeek-V3
```

### Usage

Run the `TranslationDemo` class to translate a document:

```bash
java com.gs.ep.docknight.translate.TranslationDemo <apiKey> <inputPdfPath> <outputPdfPath> <targetLanguage>
```

**Example:**
```bash
java com.gs.ep.docknight.translate.TranslationDemo "sk-..." sample.pdf translated_sample.pdf "Chinese"
```

## Advanced Settings

- **Cache**: Translation results are cached by default to reduce API costs and improve speed for re-runs.
- **Context Management**: The translator automatically sets document-level context for the LLM based on the first page content.
- **Strategies**: The system automatically switches between `SingleColumn` and `MultiColumn` analysis based on page layout detection.
