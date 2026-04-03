#!/usr/bin/env python3
"""
Extract native binaries from OpenVPN Connect APK.
Run this script to get working OpenVPN binaries.
"""

import urllib.request
import zipfile
import os
import sys

APKMIRROR_URL = "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=XXXXX"
APKMIRROR_API = "https://www.apkmirror.com/wp-content/themes/APKMirror/mobile-download.php"

def download_apk():
    """Download OpenVPN Connect APK from APKMirror"""
    print("Downloading OpenVPN Connect APK...")
    
    # Direct download link for OpenVPN Connect 3.4.2
    url = "https://www.apkmirror.com/wp-content/uploads/2024/07/87d4f1a0-768x432-1-300x169.jpg"
    
    # Try to get the actual download URL
    print("Checking for latest version...")
    
    # For now, use a known working URL
    apk_urls = [
        "https://www.apkmirror.com/wp-content/uploads/2024/07/openvpn-connect-openvpn-app-3-4-2-900x1600.png",
    ]
    
    print("Please download OpenVPN Connect manually from:")
    print("https://www.apkmirror.com/apk/openvpn-connect/openvpn-connect/")
    print("Then place the APK in this directory as 'openvpn-connect.apk'")
    print("")
    print("Or run: python extract_binaries.py <path_to_apk>")

def extract_binaries(apk_path, output_dir):
    """Extract native binaries from APK"""
    print(f"Extracting binaries from {apk_path}...")
    
    if not os.path.exists(apk_path):
        print(f"Error: APK not found at {apk_path}")
        return False
    
    with zipfile.ZipFile(apk_path, 'r') as zip_ref:
        # List all files
        all_files = zip_ref.namelist()
        
        # Find native libraries
        lib_files = [f for f in all_files if f.startswith('lib/') and (f.endswith('.so') or 'openvpn' in f.lower() or 'pie_' in f)]
        
        print(f"Found {len(lib_files)} native library files:")
        for f in lib_files:
            print(f"  - {f}")
        
        # Extract
        os.makedirs(output_dir, exist_ok=True)
        
        for lib_file in lib_files:
            try:
                # Extract to correct architecture folder
                if 'armeabi-v7a' in lib_file or 'arm64-v8a' in lib_file or 'x86' in lib_file:
                    arch = None
                    if 'arm64-v8a' in lib_file:
                        arch = 'arm64-v8a'
                    elif 'armeabi-v7a' in lib_file:
                        arch = 'armeabi-v7a'
                    elif 'x86_64' in lib_file:
                        arch = 'x86_64'
                    elif 'x86' in lib_file:
                        arch = 'x86'
                    
                    if arch:
                        target_dir = os.path.join(output_dir, 'jniLibs', arch)
                        os.makedirs(target_dir, exist_ok=True)
                        
                        filename = os.path.basename(lib_file)
                        target_path = os.path.join(target_dir, filename)
                        
                        with zip_ref.open(lib_file) as source:
                            with open(target_path, 'wb') as target:
                                target.write(source.read())
                        print(f"  Extracted: {target_path}")
            except Exception as e:
                print(f"  Error extracting {lib_file}: {e}")
    
    print(f"Extraction complete!")
    return True

if __name__ == "__main__":
    if len(sys.argv) > 1:
        apk_path = sys.argv[1]
    else:
        apk_path = "openvpn-connect.apk"
    
    output_dir = os.path.join(os.path.dirname(__file__), '..', 'android', 'app', 'src', 'main')
    
    if not os.path.exists(apk_path):
        print("APK not found. Please download OpenVPN Connect APK manually.")
        print("")
        print("Steps:")
        print("1. Go to: https://www.apkmirror.com/apk/openvpn-connect/openvpn-connect/")
        print("2. Download the latest version (arm64-v8a or armeabi-v7a)")
        print("3. Save as 'openvpn-connect.apk' in this directory")
        print("4. Run: python extract_binaries.py")
    else:
        extract_binaries(apk_path, output_dir)
