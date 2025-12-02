import asyncio
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import traceback
from pathlib import Path
import asyncssh
import aiofiles
from mythic_container.PayloadBuilder import *
from mythic_container.MythicRPC import *


class Woopsie(PayloadType):
    name = "woopsie"
    file_extension = "jar"
    author = "@haha150"
    supported_os = [
        SupportedOS.Windows,
        SupportedOS.Linux,
        SupportedOS.MacOS,
    ]
    mythic_encrypts = True
    wrapper = False
    wrapped_payloads = []
    note = "Woopsie is a cross-platform C2 agent written in Java."
    supports_dynamic_loading = False
    supports_multiple_c2_instances_in_build = False
    supports_multiple_c2_in_build = False
    
    build_parameters = [
        BuildParameter(
            name="output",
            parameter_type=BuildParameterType.ChooseOne,
            description="Payload output format (native Windows builds are experimental)",
            default_value="jar",
            choices=["jar", "native"],
            required=True,
        ),
        BuildParameter(
            name="debug",
            parameter_type=BuildParameterType.Boolean,
            description="Enable debug logging (logs all HTTP requests/responses and operations)",
            default_value=False,
            required=False,
        ),
        BuildParameter(
            name="adjust_filename",
            parameter_type=BuildParameterType.Boolean,
            description="Automatically adjust payload extension based on selected choices.",
            default_value=True,
            required=False,
        ),
        BuildParameter(
            name="windows_build_host",
            parameter_type=BuildParameterType.String,
            description="Remote Windows VM hostname/IP for native builds.",
            default_value="127.0.0.1",
            hide_conditions=[
                HideCondition(name="output", operand=HideConditionOperand.NotEQ, value="native")
            ],
            supported_os=["Windows"]
        ),
        BuildParameter(
            name="windows_build_user",
            parameter_type=BuildParameterType.String,
            description="SSH username for remote Windows VM.",
            default_value="localuser",
            hide_conditions=[
                HideCondition(name="output", operand=HideConditionOperand.NotEQ, value="native")
            ],
            supported_os=["Windows"]
        ),
        BuildParameter(
            name="windows_build_pass",
            parameter_type=BuildParameterType.String,
            description="SSH password for remote Windows VM.",
            default_value="password",
            hide_conditions=[
                HideCondition(name="output", operand=HideConditionOperand.NotEQ, value="native")
            ],
            supported_os=["Windows"]
        ),
    ]
    
    c2_profiles = ["http", "httpx"]

    c2_parameter_deviations = {
        "http": {
            "get_uri": C2ParameterDeviation(supported=False),
            "query_path_name": C2ParameterDeviation(supported=False),
        }
    }

    agent_path = pathlib.Path(".") / "woopsie" / "mythic"
    agent_code_path = pathlib.Path(".") / "woopsie" / "agent_code"
    agent_icon_path = agent_path / "agent_icon" / "woopsie.svg"

    build_steps = [
        BuildStep(step_name="Configuration", step_description="Preparing build configuration"),
        BuildStep(step_name="Compiling", step_description="Building payload with Maven"),
        BuildStep(step_name="Finalizing", step_description="Packaging final payload"),
    ]
    
    async def build(self) -> BuildResponse:
        resp = BuildResponse(status=BuildStatus.Error)
        try:
            # Get build parameters
            output_format = self.get_parameter("output")
            selected_os = self.selected_os  # Get selected OS from Mythic
            
            resp.build_message += f"Building {output_format} payload for {selected_os}...\n"

            # Set file extension based on output format and OS
            if output_format == "native":
                if selected_os == "Windows":
                    self.file_extension = "exe"
                else:
                    self.file_extension = "bin"
            else:
                self.file_extension = "jar"

            # Prepare environment variables for build (like oopsie does)
            c2 = self.c2info[0]
            profile = c2.get_c2profile()["name"]

            # Get all C2 parameters and add UUID like oopsie does
            c2_params = c2.get_parameters_dict()
            c2_params["UUID"] = self.uuid
            c2_params["profile"] = profile

            # Add build parameters
            c2_params["debug"] = str(self.get_parameter("debug"))

            # Build environment from c2_params - convert all to env format
            build_env = {}
            for key, val in c2_params.items():
                if isinstance(val, str):
                    # Handle raw_c2_config specially - fetch file content like oopsie
                    if key == "raw_c2_config":
                        response = await SendMythicRPCFileGetContent(MythicRPCFileGetContentMessage(val))
                        if response.Success:
                            val = response.Content.decode('utf-8')
                            try:
                                config = json.loads(val)
                            except json.JSONDecodeError:
                                resp.build_message = f"Failed to parse raw_c2_config JSON: {val}"
                                resp.status = BuildStatus.Error
                                return resp
                            val = json.dumps(config)
                        else:
                            resp.build_message = "Failed to get raw C2 config file"
                            resp.status = BuildStatus.Error
                            return resp
                    # Sanitize all string values (strip whitespace)
                    build_env[key.upper()] = val.strip()
                elif isinstance(val, (int, bool)):
                    build_env[key.upper()] = str(val)
                elif isinstance(val, dict):
                    # Handle headers specially to extract User-Agent
                    if key == "headers":
                        build_env["USER_AGENT"] = val.get("User-Agent", "Mozilla/5.0")
                    # Store full dict as JSON too in case needed
                    build_env[key.upper()] = json.dumps(val)
                elif isinstance(val, list):
                    # Handle lists (like callback_domains for httpx)
                    build_env[key.upper()] = json.dumps(val)
                else:
                    build_env[key.upper()] = str(val)

            # Ensure USER_AGENT is set if not already
            if "USER_AGENT" not in build_env:
                build_env["USER_AGENT"] = "Mozilla/5.0"

            await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                PayloadUUID=self.uuid,
                StepName="Configuration",
                StepStdout="Successfully prepared build configuration",
                StepSuccess=True
            ))

            # Add build environment to message for visibility
            resp.build_message += "\nBuild Configuration:\n"
            for key, value in build_env.items():
                # Mask UUID for security
                display_value = value
                resp.build_message += f"  {key}: {display_value}\n"
            resp.build_message += "\n"

            # Build logic
            if output_format == "native":
                if selected_os == "Windows":
                    resp.build_message += "Offloading Windows native build to remote VM via SSH...\n"
                    await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                        PayloadUUID=self.uuid,
                        StepName="Compiling",
                        StepStdout="Connecting to remote Windows build VM...",
                        StepSuccess=True
                    ))
                    # Get remote build parameters
                    remote_config = {
                        "host": self.get_parameter("windows_build_host"),
                        "user": self.get_parameter("windows_build_user"),
                        "pass": self.get_parameter("windows_build_pass")
                    }
                    build_result = await self.run_remote_windows_build(build_env, remote_config)
                    if not build_result["success"]:
                        resp.build_message += f"\nRemote Windows build failed: {build_result['error']}\n"
                        resp.status = BuildStatus.Error
                        await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                            PayloadUUID=self.uuid,
                            StepName="Compiling",
                            StepStdout=f"Build failed: {build_result['error']}",
                            StepSuccess=False
                        ))
                        return resp
                    await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                        PayloadUUID=self.uuid,
                        StepName="Compiling",
                        StepStdout="Successfully built native Windows executable",
                        StepSuccess=True
                    ))
                    resp.build_message += f"Remote build command: {build_result['command']}\n"
                    output_path = build_result["path"]
                else:
                    # Linux: Use GraalVM Native Image
                    resp.build_message += f"Building native {selected_os} executable with GraalVM...\n"
                    build_result = await self.run_native_build(selected_os, build_env)
                    if not build_result["success"]:
                        resp.build_message += f"\nNative build failed: {build_result['error']}\n"
                        resp.status = BuildStatus.Error
                        await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                            PayloadUUID=self.uuid,
                            StepName="Compiling",
                            StepStdout=f"Build failed: {build_result['error']}",
                            StepSuccess=False
                        ))
                        return resp
                    await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                        PayloadUUID=self.uuid,
                        StepName="Compiling",
                        StepStdout=f"Successfully built native {selected_os} executable",
                        StepSuccess=True
                    ))
                    resp.build_message += f"Build command: {build_result['command']}\n"
                    output_path = build_result["path"]
            else:
                resp.build_message += "Building JAR with Maven...\n"
                build_result = await self.run_maven_build(build_env)
                if not build_result["success"]:
                    resp.build_message += f"\nMaven build failed: {build_result['error']}\n"
                    resp.status = BuildStatus.Error
                    await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                        PayloadUUID=self.uuid,
                        StepName="Compiling",
                        StepStdout=f"Build failed: {build_result['error']}",
                        StepSuccess=False
                    ))
                    return resp
                await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                    PayloadUUID=self.uuid,
                    StepName="Compiling",
                    StepStdout="Successfully built JAR with Maven",
                    StepSuccess=True
                ))
                resp.build_message += f"Build command: {build_result['command']}\n"
                resp.build_message += f"Build command: {build_result['command']}\n"
                jar_path = self.agent_code_path / "target" / "woopsie.jar"
                if not jar_path.exists():
                    resp.build_message += "JAR file not found after build\n"
                    resp.status = BuildStatus.Error
                    await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                        PayloadUUID=self.uuid,
                        StepName="Finalizing",
                        StepStdout="JAR file not found after build",
                        StepSuccess=False
                    ))
                    return resp
                output_path = jar_path

            # Read the final payload
            resp.build_message += "Reading final payload...\n"
            with open(output_path, "rb") as f:
                resp.payload = f.read()

            await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                PayloadUUID=self.uuid,
                StepName="Finalizing",
                StepStdout=f"Successfully packaged {output_format} payload",
                StepSuccess=True
            ))

            resp.status = BuildStatus.Success
            resp.build_message += f"\n✓ Successfully built {output_format} payload for {selected_os}\n"

            # Adjust filename based on output format and OS
            if self.get_parameter("adjust_filename"):
                resp.updated_filename = self.adjust_file_name(self.filename, output_format, selected_os)

        except Exception as e:
            resp.build_message = f"Error building payload: {str(e)}\n{traceback.format_exc()}"
            resp.status = BuildStatus.Error

        return resp
        

    async def run_remote_windows_build(self, build_env: dict, remote_config: dict) -> dict:
        """Offload Windows native build to remote VM via SSH and fetch the .exe"""
        # Get configuration from parameters
        remote_host = remote_config["host"]
        remote_user = remote_config["user"]
        remote_pass = remote_config["pass"]
        remote_dir = "C:/woopsie/agent_code"
        remote_target = f"{remote_dir}/target/woopsie.exe"
        local_target = self.agent_code_path / "target" / "woopsie.exe"
        build_cmd = "mvn clean package -Pnative -Dos.detected=windows"
        
        # Prepare env vars for Windows batch - properly escape values
        def escape_batch_value(key, value):
            """Escape special characters for Windows batch set command"""
            value = str(value)
            
            # Special handling for JSON values (AESPSK, HEADERS, etc.) - don't add extra quotes
            if value.strip().startswith('{') and value.strip().endswith('}'):
                # Just escape special batch characters, no outer quotes
                value = value.replace('&', '^&').replace('|', '^|').replace('<', '^<').replace('>', '^>')
                value = value.replace('(', '^(').replace(')', '^)').replace('%', '%%')
                return value
            
            # Regular string escaping
            value = value.replace('&', '^&').replace('|', '^|').replace('<', '^<').replace('>', '^>')
            value = value.replace('(', '^(').replace(')', '^)').replace('%', '%%')
            # Quote if contains spaces
            if ' ' in value:
                value = f'"{value}"'
            return value
        
        env_str = ' '.join(f"set {k}={escape_batch_value(k, v)} &&" for k, v in build_env.items())
        remote_command = f"cd {remote_dir} && {env_str} {build_cmd}"

        try:
            async with asyncssh.connect(
                remote_host, 
                username=remote_user, 
                password=remote_pass, 
                known_hosts=None,
                connect_timeout=30
            ) as conn:
                # Ensure parent directory exists
                await conn.run('if not exist "C:\\woopsie" mkdir "C:\\woopsie"', check=False)
                # Remove remote agent_code directory if it exists
                await conn.run(f'rmdir /S /Q "{remote_dir}"', check=False)
                # Create the directory
                await conn.run(f'mkdir "{remote_dir}"', check=True)
                # Recursively upload local agent_code to remote
                async with conn.start_sftp_client() as sftp:
                    for root, dirs, files in os.walk(self.agent_code_path):
                        rel_root = os.path.relpath(root, self.agent_code_path)
                        remote_root = remote_dir if rel_root == '.' else f'{remote_dir}/{rel_root.replace(os.sep, "/")}'
                        try:
                            await sftp.mkdir(remote_root)
                        except Exception:
                            pass  # Directory may already exist
                        for file in files:
                            local_file = os.path.join(root, file)
                            remote_file = f'{remote_root}/{file}'
                            async with aiofiles.open(local_file, "rb") as lf, sftp.open(remote_file, "wb") as rf:
                                await rf.write(await lf.read())
                # Run the build command with timeout (10 minutes for native build)
                result = await conn.run(remote_command, check=False, timeout=600)
                if result.exit_status != 0:
                    error_msg = f"Remote build failed with exit code {result.exit_status}\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}"
                    return {"success": False, "error": error_msg, "command": remote_command}
                # SCP the built .exe back
                async with conn.start_sftp_client() as sftp:
                    async with sftp.open(remote_target, "rb") as remote_file:
                        data = await remote_file.read()
                        # Write to local target
                        local_target.parent.mkdir(parents=True, exist_ok=True)
                        async with aiofiles.open(local_target, "wb") as f:
                            await f.write(data)
                if not local_target.exists():
                    return {"success": False, "error": f"Failed to fetch {remote_target}", "command": remote_command}
                return {"success": True, "path": local_target, "command": remote_command}
        except asyncssh.Error as e:
            return {"success": False, "error": f"SSH connection failed: {str(e)}", "command": remote_command}
        except asyncio.TimeoutError:
            return {"success": False, "error": "Build timeout (exceeded 10 minutes)", "command": remote_command}
        except Exception as e:
            return {"success": False, "error": f"Unexpected error: {str(e)}", "command": remote_command}
    
    def adjust_file_name(self, filename, output_type, selected_os):
        """Adjust filename based on output type and OS"""
        filename_pieces = filename.split(".")
        original_filename = ".".join(filename_pieces[:-1])
        
        if output_type == "native":
            if selected_os == "Linux":
                return original_filename + ".bin"
            elif selected_os == "Windows":
                return original_filename + ".exe"
            else:
                return original_filename + ".bin"
        else:  # jar
            return original_filename + ".jar"
    
    def _shell_quote(self, value):
        """Safely quote a value for bash shell as a single-quoted string."""
        if not isinstance(value, str):
            value = str(value)
        # Replace every single quote with: '\''
        return "'" + value.replace("'", "'\\''") + "'"

    async def run_maven_build(self, build_env: dict) -> dict:
        """Run Maven JAR build"""
        try:
            # Merge build environment with system environment
            env = os.environ.copy()
            env.update(build_env)

            # Build the full shell command string with env vars, shell-quoted
            env_str = ' '.join(f"{k}={self._shell_quote(v)}" for k, v in build_env.items())
            command = f'{env_str} mvn clean package -q' if env_str else 'mvn clean package -q'

            process = await asyncio.create_subprocess_exec(
                "mvn", "clean", "package", "-q",
                cwd=str(self.agent_code_path),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env
            )

            stdout, stderr = await process.communicate()

            if process.returncode != 0:
                stdout_text = stdout.decode() if stdout else ""
                stderr_text = stderr.decode() if stderr else ""
                return {
                    "success": False,
                    "error": f"Maven failed with code {process.returncode}\n\nSTDOUT:\n{stdout_text}\n\nSTDERR:\n{stderr_text}"
                }

            return {"success": True, "command": command}

        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def run_native_build(self, selected_os: str, build_env: dict) -> dict:
        """Run GraalVM Native Image build"""
        try:
            # Set up environment for native build
            env = os.environ.copy()
            env.update(build_env)
            env["GRAALVM_HOME"] = "/opt/graalvm"
            env["JAVA_HOME"] = "/opt/graalvm"

            # Configure for Windows cross-compilation if needed
            if selected_os == "Windows":
                env["CC"] = "x86_64-w64-mingw32-gcc"
                env["CXX"] = "x86_64-w64-mingw32-g++"

            # Only include relevant env vars in the command string
            relevant_keys = set(build_env.keys()) | {"GRAALVM_HOME", "JAVA_HOME", "CC", "CXX"}
            env_str = ' '.join(f"{k}={self._shell_quote(env[k])}" for k in relevant_keys if k in env)
            command = f'{env_str} mvn clean package -Pnative -Dos.detected={selected_os.lower()}' if env_str else f'mvn clean package -Pnative -Dos.detected={selected_os.lower()}'

            process = await asyncio.create_subprocess_exec(
                "mvn",
                "clean",
                "package",
                "-Pnative",
                f"-Dos.detected={selected_os.lower()}",
                cwd=str(self.agent_code_path),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env
            )

            stdout, stderr = await process.communicate()

            if process.returncode != 0:
                stdout_text = stdout.decode() if stdout else ""
                stderr_text = stderr.decode() if stderr else ""
                return {
                    "success": False,
                    "error": f"Native build failed with code {process.returncode}\n\nSTDOUT:\n{stdout_text}\n\nSTDERR:\n{stderr_text}"
                }

            if selected_os == "Windows":
                native_path = self.agent_code_path / "target" / "woopsie.exe"
            else:
                native_path = self.agent_code_path / "target" / "woopsie"

            if not native_path.exists():
                return {
                    "success": False,
                    "error": f"Native executable not found at {native_path}"
                }

            return {
                "success": True,
                "path": native_path,
                "command": command
            }

        except Exception as e:
            return {"success": False, "error": str(e)}


