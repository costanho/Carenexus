import os
import re

TEST_DIR = '/Users/dzambeti/IdeaProjects/Carenexus/backend/carenexus-api/src/test/java/com/carenexus/api'

IMPORTS = {
    'JwtUtil': 'com.carenexus.api.auth.config.JwtUtil',
    'UserRepository': 'com.carenexus.api.auth.repository.UserRepository',
    'RefreshTokenRepository': 'com.carenexus.api.auth.repository.RefreshTokenRepository',
    'CareTeamRepository': 'com.carenexus.api.core.repository.CareTeamRepository',
    'DependentAccessRepository': 'com.carenexus.api.core.repository.DependentAccessRepository',
    'AuditLogRepository': 'com.carenexus.api.common.repository.AuditLogRepository',
    'User': 'com.carenexus.api.auth.model.User',
    'CareTeam': 'com.carenexus.api.core.model.CareTeam',
    'DependentAccess': 'com.carenexus.api.core.model.DependentAccess',
    'RefreshToken': 'com.carenexus.api.auth.model.RefreshToken',
    'AuditLog': 'com.carenexus.api.common.model.AuditLog',
    'RelationshipService': 'com.carenexus.api.core.service.RelationshipService',
    'AuthorizationService': 'com.carenexus.api.common.service.AuthorizationService',
    'PostgresTestContainerConfig': 'com.carenexus.api.common.config.PostgresTestContainerConfig',
    'TestDataBuilder': 'com.carenexus.api.common.util.TestDataBuilder'
}

for root, dirs, files in os.walk(TEST_DIR):
    for file in files:
        if file.endswith('.java'):
            filepath = os.path.join(root, file)
            with open(filepath, 'r') as f:
                content = f.read()
            
            # Remove old bad imports
            content = re.sub(r'import com\.carenexus\.api\.repository\..*?;\n', '', content)
            content = re.sub(r'import com\.carenexus\.api\.model\..*?;\n', '', content)
            
            # Add missing imports
            new_imports = []
            for class_name, full_pkg in IMPORTS.items():
                # If class name is used in the file and not imported
                if re.search(r'\b' + class_name + r'\b', content) and f"import {full_pkg};" not in content:
                    # check if it's not the same package
                    current_pkg_match = re.search(r'^package\s+(.*?);', content, re.MULTILINE)
                    if current_pkg_match:
                        current_pkg = current_pkg_match.group(1)
                        target_pkg = full_pkg.rsplit('.', 1)[0]
                        if current_pkg != target_pkg:
                            new_imports.append(f"import {full_pkg};")
            
            if new_imports:
                # Add imports after the package declaration
                import_block = "\n".join(new_imports)
                content = re.sub(r'^(package\s+.*?;)', r'\1\n\n' + import_block, content, count=1, flags=re.MULTILINE)
                
            with open(filepath, 'w') as f:
                f.write(content)

