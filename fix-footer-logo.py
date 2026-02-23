"""
Run this once from C:\Users\sivag\Desktop\cms\Acquira to replace the 
hardcoded base64 logo in footer.html with the Thymeleaf variable.

Usage:  python fix-footer-logo.py
"""
import re, sys, os

footer_path = os.path.join(os.path.dirname(__file__),
    r'acquira-pdf\src\main\resources\templates\partials\footer.html')

with open(footer_path, 'r', encoding='utf-8') as f:
    content = f.read()

pattern = r'<img src="data:image/png;base64,[^"]*" alt="AFS" style="height: 18px; width: auto;" />'
replacement = '<img th:if="${afsLogoBlack != null}" th:src="${afsLogoBlack}" alt="AFS" style="height: 18px; width: auto;" />'

new_content, count = re.subn(pattern, replacement, content)

if count == 0:
    print("WARNING: No hardcoded base64 logo found — footer may already be updated.")
    sys.exit(0)

with open(footer_path, 'w', encoding='utf-8') as f:
    f.write(new_content)

print(f"SUCCESS: Replaced {count} hardcoded base64 logo(s) in footer.html")
