import React, { useCallback, useState } from 'react';
import { Upload, File, X, CheckCircle } from 'lucide-react';
import './FileDropzone.css';

const FileDropzone = ({ onFileSelect, type }) => {
    const [isDragging, setIsDragging] = useState(false);
    const [selectedFile, setSelectedFile] = useState(null);

    const handleDragOver = (e) => {
        e.preventDefault();
        setIsDragging(true);
    };

    const handleDragLeave = (e) => {
        e.preventDefault();
        setIsDragging(false);
    };

    const handleDrop = (e) => {
        e.preventDefault();
        setIsDragging(false);

        if (e.dataTransfer.files && e.dataTransfer.files[0]) {
            handleFile(e.dataTransfer.files[0]);
        }
    };

    const handleFileInput = (e) => {
        if (e.target.files && e.target.files[0]) {
            handleFile(e.target.files[0]);
        }
    };

    const handleFile = (file) => {
        // Basic validation
        if (!file.name.match(/\.(xlsx|xls|csv)$/)) {
            alert("Please upload an Excel or CSV file.");
            return;
        }
        setSelectedFile(file);
        onFileSelect(file);
    };

    const clearFile = (e) => {
        e.stopPropagation();
        setSelectedFile(null);
        onFileSelect(null);
    };

    return (
        <div
            className={`dropzone ${isDragging ? 'dragging' : ''} ${selectedFile ? 'has-file' : ''}`}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            onDrop={handleDrop}
            onClick={() => document.getElementById(`fileInput-${type}`).click()}
        >
            <input
                type="file"
                id={`fileInput-${type}`}
                style={{ display: 'none' }}
                onChange={handleFileInput}
                accept=".xlsx,.xls,.csv"
            />

            {selectedFile ? (
                <div className="file-info">
                    <File className="icon-file" size={40} />
                    <div className="file-details">
                        <span className="file-name">{selectedFile.name}</span>
                        <span className="file-size">{(selectedFile.size / 1024).toFixed(2)} KB</span>
                    </div>
                    <button className="btn-remove" onClick={clearFile}>
                        <X size={20} />
                    </button>
                </div>
            ) : (
                <div className="upload-prompt">
                    <div className="icon-wrapper">
                        <Upload size={32} />
                    </div>
                    <h3>Click or Drag file to upload</h3>
                    <p>Support for XLSX, CSV</p>
                </div>
            )}
        </div>
    );
};

export default FileDropzone;
